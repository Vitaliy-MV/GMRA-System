package org.gmra.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.gmra.config.BootApplication;
import org.gmra.dao.SystemDao;
import org.gmra.service.MathCalculationService;
import org.gmra.service.Tools;
import org.gmra.service.IscDataCalculator.IscResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.stream.Stream;
/*
 * Собираем данные о температуре воды Boiling River по дням
 * В таблицу usgs_water_stats пишем среднее и медианное значение по дням
 * В таблицу statistical_baselines пишем глобавльное среднее за весь период и отклонение от среднего (сигма).  
 */
@Service
public class UsgsWaterClient {
	
	// sites=06190540 (Yellowstone), parameterCd=00010 (Water temperature in °C) || sites=06036905 || sites=06190540
	private static final String USGS_URL_TEMPLATE = "https://waterservices.usgs.gov/nwis/iv/?format=json&sites=06036905&parameterCd=00010&startDT=%s&endDT=%s";
	private static final String DIR = BootApplication.DIRSysFiles + "Data USGS/Firehole River/";

	private final RestTemplate restTemplate = new RestTemplate();
	private final ObjectMapper objectMapper = new ObjectMapper();

	public static class UsgsWaterResult {
		public final ArrayList<Double> mean;
		public final ArrayList<Double> median;
		public final ArrayList<LocalDate> listDates;

		public UsgsWaterResult(ArrayList<Double> mean, 
				ArrayList<Double> median, ArrayList<LocalDate> listDates) {
			
			this.mean = mean;
			this.median = median;
			this.listDates = listDates;
		}
	}

	private final MathCalculationService mathCalculationService;
	private final SystemDao systemDao;
	private final Tools tools;

	public UsgsWaterClient(SystemDao systemDao, Tools tools, MathCalculationService mathCalculationService) {
		this.mathCalculationService = mathCalculationService;
		this.systemDao = systemDao;
		this.tools = tools;
	}
	
	// @Scheduled(fixedDelay = 60000)
	// Запуск каждый день в 00:01 UTC
	// @Scheduled(cron = "0 01 0 * * *", zone = "UTC")
	@Scheduled(cron = "0 05 7 * * *", zone = "UTC")
    public void fetchAndProcessUsgsData() {
        if (!mathCalculationService.isSystemReady()) {
            return;
        }
        int maxRetries = 10; //  connection attempts
        int retryCount = 0;
        boolean success = false;
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        LocalDate yesterday = today.minusDays(1);

        String fileName = String.format("%04d-%02d-01.jsonl", yesterday.getYear(), yesterday.getMonthValue());
        Path filePath = Paths.get(DIR, fileName);
        
        // Strictly yesterday from the first to the last second.
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String startDT = yesterday.format(formatter) + "T00:00:00.000";
        String endDT = yesterday.format(formatter) + "T23:59:59.000";

        String url = String.format(USGS_URL_TEMPLATE, startDT, endDT);
        while (retryCount < maxRetries && !success) {
            try {
                tools.writeLog("Fetching water temperature data from USGS (Attempt " + (retryCount + 1) + "): " + url);
                String jsonResponse = restTemplate.getForObject(url, String.class);
                if (jsonResponse == null || jsonResponse.trim().isEmpty()) {
                    tools.writeLog("USGS returned empty response.");
                    return; 
                }
                JsonNode root = objectMapper.readTree(jsonResponse);
                String minifiedJson = objectMapper.writeValueAsString(root);
                processAndSaveWaterData(jsonResponse, yesterday);
                tools.writeDataFile(minifiedJson, filePath);
                success = true; // Loaded and write
            } 
            catch (Exception e) {
                retryCount++;
                tools.writeLog("Error in daily USGS Water sync: " + e.getMessage());
                if (retryCount < maxRetries) {
                    tools.writeLog("USGS Server unavailable (503). Retrying in 10 minutes...");
                    try {
                        Thread.sleep(600000); // 10 min.
                    } 
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } 
                else {
                    tools.writeLog("Max retries reached. USGS Water sync completely failed for " + yesterday);
                }
            }
        }
    }

	private void processAndSaveWaterData(String jsonResponse, LocalDate targetDate) throws Exception {
		JsonNode root = objectMapper.readTree(jsonResponse);
		JsonNode timeSeriesNode = root.path("value").path("timeSeries");

		if (timeSeriesNode.isMissingNode() || !timeSeriesNode.isArray() || timeSeriesNode.size() == 0) {
			tools.writeLog("USGS data parsing failed: timeSeries node is missing or empty.");
			return;
		}

		JsonNode valuesNode = timeSeriesNode.get(0).path("values").get(0).path("value");
		if (valuesNode.isMissingNode() || !valuesNode.isArray() || valuesNode.size() == 0) {
			tools.writeLog("USGS data parsing failed: values node is empty for date " + targetDate);
			return;
		}

		DescriptiveStatistics stats = new DescriptiveStatistics();
		for (JsonNode dataPoint : valuesNode) {
			String valStr = dataPoint.path("value").asText();
			try {
				double temp = Double.parseDouble(valStr);
				stats.addValue(temp);
			} 
			catch (NumberFormatException e) {}
		}
		if (stats.getN() > 0) {
			double meanTemp = stats.getMean();
			double medianTemp = stats.getPercentile(50);
			systemDao.updateUsgsWaterTemperature(targetDate, meanTemp, medianTemp);
			tools.writeLog(String.format("Updated USGS Water Temp for %s: Mean=%.2f, Median=%.2f (Records: %d)",
					targetDate, meanTemp, medianTemp, stats.getN()));
			
			updateBaselines();
		}
	}

	private void updateBaselines() {
		List<Double> historicalWaterTemps = systemDao.getAllUsgsWaterData();
		if (historicalWaterTemps != null && !historicalWaterTemps.isEmpty()) {
			DescriptiveStatistics stats = new DescriptiveStatistics();
			for (Double val : historicalWaterTemps) {
				if (val != null && !Double.isNaN(val)) {
					stats.addValue(val);
				}
			}
			double mean = stats.getMean(); // global
			double sigma = stats.getStandardDeviation(); 
			systemDao.saveBaseline("usgs_water_temp", mean, sigma);
			mathCalculationService.loadSigmaToCache("water_temp", sigma);
			tools.writeLog(String.format("Baseline [water_temp]: Mean = %.3f, Sigma = %.3f", mean, sigma));
		}
	}

	public UsgsWaterResult loadEndSetDataFromFiles() {
		// Automatic sorting of dates in ascending order
		TreeMap<LocalDate, DescriptiveStatistics> dailyStats = new TreeMap<>();
		ObjectMapper mapper = new ObjectMapper();
		try {
			File folder = new File(DIR);
			String[] path = folder.list();
			if (path == null) return null;
			Arrays.sort(path);

			for (String nameFile : path) {
				File file = new File(DIR + nameFile);
				byte[] byteArray = Files.readAllBytes(file.toPath());
				String text = new String(byteArray, StandardCharsets.UTF_8);
				Stream<String> allDataLines = text.lines();
				Iterator<String> it = allDataLines.iterator();
				
				while (it.hasNext()) {
					String line = it.next();
					if (line.trim().isEmpty())
						continue;

					JsonNode dailyData = mapper.readTree(line);
					JsonNode timeSeriesNode = dailyData.path("value").path("timeSeries");
					if (timeSeriesNode.isMissingNode() || !timeSeriesNode.isArray() || timeSeriesNode.size() == 0)
						continue;

					JsonNode valuesNode = timeSeriesNode.get(0).path("values").get(0).path("value");
					if (valuesNode.isMissingNode() || !valuesNode.isArray() || valuesNode.size() == 0)
						continue;
					
					// Проходим по КАЖДОМУ замеру индивидуально
					for (JsonNode dataPoint : valuesNode) {
						String fullDateTime = dataPoint.path("dateTime").asText();
						// Получаем дату конкретно этого замера
						LocalDate date = LocalDate.parse(fullDateTime.split("T")[0]);
						String valStr = dataPoint.path("value").asText();
						try {
							double temp = Double.parseDouble(valStr);
							// Добавляем значение в статистику конкретного дня 
							// (если дня еще нет в Map, он создастся автоматически)
							dailyStats.computeIfAbsent(date, k -> new DescriptiveStatistics()).addValue(temp);
						} 
						catch (NumberFormatException e) {}
					}
				}
			}
		}
		catch (IOException e) {
			e.printStackTrace();
		}
		ArrayList<Double> mean = new ArrayList<>();
		ArrayList<Double> median = new ArrayList<>();
		ArrayList<LocalDate> dateList = new ArrayList<>();

		for (Entry<LocalDate, DescriptiveStatistics> entry : dailyStats.entrySet()) {
			dateList.add(entry.getKey());
			mean.add(entry.getValue().getMean());
			median.add(entry.getValue().getPercentile(50));
		}
		return new UsgsWaterResult (mean, median, dateList);
	}
	
	/*
	public void downloadHistoricalWaterData() {
		// sites=06190540 == 2012-09-01
		// sites=06036905 == 2011-10-01
	    LocalDate startDate = LocalDate.of(2026, 10, 1);
	    LocalDate endDate = LocalDate.of(2026, 10, 5); // LocalDate.now(ZoneId.of("UTC"));
	    LocalDate currentMonth = startDate;

	    System.out.println("🚀 Начинаем загрузку исторического архива USGS Water (с 2011 года)...");
	    while (currentMonth.isBefore(endDate) || currentMonth.getMonth().equals(endDate.getMonth())) {
	        LocalDate startOfMonth = currentMonth.withDayOfMonth(1);
	        LocalDate endOfMonth = currentMonth.withDayOfMonth(currentMonth.lengthOfMonth());

	        // Ограничиваем конец месяца сегодняшним днем, если это текущий месяц
	        if (endOfMonth.isAfter(endDate)) {
	            endOfMonth = endDate;
	        }

	        String fileName = String.format("%04d-%02d-01.jsonl", startOfMonth.getYear(), startOfMonth.getMonthValue());
	        Path filePath = Paths.get(DIR, fileName);

	        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	        String url = String.format(USGS_URL_TEMPLATE, startOfMonth.format(formatter), endOfMonth.format(formatter));

	        System.out.println("Запрашиваем данные за: " + startOfMonth.format(formatter) + "...");
	        
	        try {
	            String jsonResponse = restTemplate.getForObject(url, String.class);
	            if (jsonResponse != null && !jsonResponse.trim().isEmpty()) {
	                JsonNode root = objectMapper.readTree(jsonResponse);
	                String minifiedJson = objectMapper.writeValueAsString(root);
	                
	                // Записываем файл (один файл на месяц, одна строка JSONL)
	                tools.writeDataFile(minifiedJson, filePath);
	            }
	         
	            // Таймаут 5 секунд между запросами
	            Thread.sleep(5000); 
	        } 
	        catch (Exception e) {
	        	System.out.println("⚠️ Ошибка при загрузке за " + startOfMonth + ": " + e.getMessage());
	        }

	        currentMonth = currentMonth.plusMonths(1);
	    }
	    tools.writeLog("✅ Загрузка архива USGS Water завершена.");
	}
	*/
}