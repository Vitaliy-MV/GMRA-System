package org.gmra.integration;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.gmra.config.BootApplication;
import org.gmra.dao.SystemDao;
import org.gmra.service.DataInitializationService;
import org.gmra.service.IscDataCalculator;
import org.gmra.service.MathCalculationService;
import org.gmra.service.Tools;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
//https://developers.google.com/maps/demo-key?hl=ru#supported-features
//https://console.cloud.google.com/home/dashboard?project=gmp-demo-project-400705961
@Service
public class IscClient {

    private final RestTemplate restTemplate = new RestTemplate();
    private final MathCalculationService mathCalculationService;
    private final IscDataCalculator iscDataCalculator;
    private final SystemDao systemDao;
    private final Tools tools;

    private static final String DIR = BootApplication.DIRSysFiles+"Data ISC/";
    private static final String ISC_URL_TEMPLATE = "https://www.isc.ac.uk/fdsnws/event/1/query?starttime=%s&endtime=%s&minmag=1.0&format=text";
    //https://www.isc.ac.uk/fdsnws/event/1/
    
    public IscClient(SystemDao systemDao, Tools tools,
    	    MathCalculationService mathCalculationService,
    	    IscDataCalculator iscDataCalculator) {
    	
    	this.iscDataCalculator = iscDataCalculator;
    	this.mathCalculationService = mathCalculationService;
        this.systemDao = systemDao;
        this.tools = tools;
    }
    
    //@Scheduled(fixedDelay = 60000)
    // (0 0 1 - It's 5:00 AM Jerusalem time)
    // (0 05 0 - It's 3:05 AM Jerusalem time)
    //@Scheduled(cron = "0 05 0 * * *", zone = "UTC")
    @Scheduled(cron = "0 05 5 * * *", zone = "UTC")
    public void fetchAndAppendIscData() {
    	if (!mathCalculationService.isSystemReady()) {
            return; // System on start
        }
        try {
            LocalDate today = LocalDate.now(ZoneId.of("UTC"));
            LocalDate yesterday = today.minusDays(1);
            String dateKey = yesterday.toString();
            
            String fileName = String.format("%04d-%02d-01.txt", yesterday.getYear(), yesterday.getMonthValue());
            Path filePath = Paths.get(DIR, fileName);
            
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            String url = String.format(ISC_URL_TEMPLATE, yesterday.format(formatter), today.format(formatter));
            tools.writeLog("Fetching data from ISC: " + url);
            String response = restTemplate.getForObject(url, String.class);
            if (response == null || response.trim().isEmpty()) {
                tools.writeLog("ISC returned empty response.");
                return;
            }
            String cleanResponse = response.replaceAll("(?m)^#.*\\n?", "");
            tools.writeDataFile(cleanResponse, filePath);
            tools.writeLog("ISC Seismological data calculation...");
            /////   Block access to the API  /////////
            mathCalculationService.setSystemReady(false);
            IscDataCalculator.IscResult iscResult = iscDataCalculator.calculateAll();
            if (iscResult != null && iscResult.countByDate.containsKey(dateKey)) {
                int dailyCount = iscResult.countByDate.get(dateKey);
                if (dailyCount > 0) {
                    double avgDepth = iscResult.depthByDate.get(dateKey);
                    double sumMagnitude = iscResult.magnitudeByDate.get(dateKey);
                    double maxMagnitude = iscResult.maxMagnitudeByDate.get(dateKey);
                    double bValue = iscResult.bValueByDate.get(dateKey);
                    double benioffByDate = iscResult.benioffByDate.get(dateKey);
                    systemDao.updateEarthquakeStatistics(yesterday, avgDepth,
                    		sumMagnitude, maxMagnitude, dailyCount, bValue, benioffByDate);
                    tools.writeLog("Updated daily seismological records: " + dailyCount);
                    
                    updateBaselines();
                    mathCalculationService.setScoringConfig(DataInitializationService.windowSize,
                    										DataInitializationService.TARGET_MAGNITUDE);
                    tools.writeLog("Daily ISC sync, file append, and recalibration completed successfully.");
                    mathCalculationService.setSystemReady(true);
                }
            } 
            else {
            	tools.writeLog("Data ISC for " + dateKey + " not found after parsing.");
            }  
        } catch (Exception e) { tools.writeLog("Error in daily ISC sync: " + e.getMessage()); }
    }

	private void updateBaselines() {
		List<Double> historicalDepths = systemDao.getAllDepths();
        calculateForParameter("global_depth", historicalDepths);
        
        List<Double> historicalMagnitudes = systemDao.getAllMagnitudes();
        calculateForParameter("sum_magnitude", historicalMagnitudes);
        tools.writeLog("Baseline calculation completed.");
	}
	
	  private void calculateForParameter(String paramName, List<Double> dataPoints) {
	        if (dataPoints == null || dataPoints.isEmpty()) return;
	        DescriptiveStatistics stats = new DescriptiveStatistics();
	        for (Double val : dataPoints) {
	            if (val != null && !Double.isNaN(val)) {
	                stats.addValue(val);
	            }
	        }
	        double mean = stats.getMean();
	        double sigma = stats.getStandardDeviation(); 
	        systemDao.saveBaseline(paramName, mean, sigma);
	        mathCalculationService.loadSigmaToCache(paramName, sigma);
	        tools.writeLog(String.format("Baseline [%s]: Mean = %.3f, Sigma = %.3f", 
	                                     paramName, mean, sigma));
	    }
}