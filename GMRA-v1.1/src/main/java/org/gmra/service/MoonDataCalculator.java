package org.gmra.service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.gmra.config.BootApplication;
import org.springframework.stereotype.Service;

@Service
public class MoonDataCalculator {
    
    private static final double ECCENTRICITY = 0.0549;
    private static final Pattern DISTANCE_PATTERN = Pattern.compile("\"distance\"\\s*:\\s*(\\d+)");
    private final static String DIR = BootApplication.DIRSysFiles + "NASA Moon/";

    public static class MoonResult {
        public final List<String> listDates;
        public final Map<String, Double> areaByDate; 			// Area from extremum to extremum 
        public final Map<String, Double> phaseSpeeds; 			// Speed from extremum to extremum 
        public final Map<String, Double> dailySpeeds; 			// Average Speed by day
        public final Map<String, Double> dailyDistance; 		// Average Distance by day
        public final Map<String, Double> dailySpeedDerivative; 	// Daily Acceleration (Speed Derivative)

        public MoonResult(List<String> listDates, Map<String, Double> areaByDate, Map<String, Double> dailyDistance, 
             Map<String, Double> phaseSpeeds, Map<String, Double> dailySpeeds, Map<String, Double> dailySpeedDerivative) {
            this.listDates = listDates;
            this.areaByDate = areaByDate;
            this.phaseSpeeds = phaseSpeeds;
            this.dailySpeeds = dailySpeeds;
            this.dailyDistance = dailyDistance;
            this.dailySpeedDerivative = dailySpeedDerivative;
        }
    }

    public MoonResult calculateAll() {
        ArrayList<String> listDates = new ArrayList<>();
        Map<String, Double> areaByDate = new HashMap<>();
        Map<String, Double> phaseSpeeds = new HashMap<>();
        Map<String, Double> dailySpeeds = new HashMap<>();
        Map<String, Double> dailyDistance = new HashMap<>();
        Map<String, Double> dailySpeedDerivative = new HashMap<>();
        processFiles(listDates, areaByDate, phaseSpeeds, dailySpeeds, dailyDistance);
        sortDates(listDates);
        
        List<String> allDays = new ArrayList<>(dailySpeeds.keySet());
        java.util.Collections.sort(allDays);
        dailySpeedDerivative = getSpeedDerivative(allDays, dailySpeeds, dailySpeedDerivative);
        return new MoonResult(listDates, areaByDate, dailyDistance, phaseSpeeds, dailySpeeds, dailySpeedDerivative);
    }

    private Map<String, Double> getSpeedDerivative(List<String> allDays, Map<String, Double> dailySpeeds,
			Map<String, Double> dailySpeedDerivative) {
    	for (int i = 0; i < allDays.size(); i++) {
            String currentDay = allDays.get(i);
            if (i == 0)
                dailySpeedDerivative.put(currentDay, 0.0);
            else {
                String prevDay = allDays.get(i - 1);
                double currentSpeed = dailySpeeds.get(currentDay);
                double prevSpeed = dailySpeeds.get(prevDay);
                double speed = Math.abs(currentSpeed - prevSpeed);
                dailySpeedDerivative.put(currentDay, speed);
            }
        }
		return dailySpeedDerivative;
	}

	private void processFiles(ArrayList<String> listDates, Map<String, Double> areaByDate, 
    		Map<String, Double> phaseSpeeds, Map<String, Double> dailySpeeds, Map<String, Double> dailyDistance) {
        int prevDist = -1;
        int direction = 0; // 1 = Apogee, -1 = Perigee
        int lastExtremeDist = -1;
        String prevLine = null;
        
        int dailyCount = 0;
        double dailySpeedSum = 0;
        double dailyDistanceSum = 0;
        String currentDay = null;
        double phaseSpeedSum = 0;
        int phaseSpeedCount = 0;
        
        try {
            File folder = new File(DIR);
            String[] path = folder.list();
            if (path == null) return;
            Arrays.sort(path);
            for (String nameFile : path) {
                File file = new File(DIR + nameFile);
                byte[] byteArray = Files.readAllBytes(file.toPath());
                String text = new String(byteArray, StandardCharsets.UTF_8);
                
                try (Stream<String> allDataLines = text.lines()) {
                    for (String line : (Iterable<String>) allDataLines::iterator) {
                        if (line.contains("Date") || line.isBlank() || line.length() < 2) 
                        	continue;
                        int currDist = extractDistance(line);
                        if (currDist == -1) continue;

                        String currDay = setFormatDate(line);
                        if (currDay == null) continue;
                        
                        if (prevDist != -1) {
                            double speed = Math.abs(currDist - prevDist);
                            // 1. Aggregation by days
                            if (currentDay == null) currentDay = currDay;
                            if (!currDay.equals(currentDay)) {
                                dailySpeeds.put(currentDay, dailySpeedSum / dailyCount);
                                dailyDistance.put(currentDay, dailyDistanceSum / dailyCount);         
                                dailyDistanceSum = 0;
                                dailySpeedSum = 0;
                                dailyCount = 0;
                                currentDay = currDay;
                            }
                            dailyDistanceSum += currDist;
                            dailySpeedSum += speed;
                            dailyCount++;
                            
                            // 2. Aggregation by extreme
                            phaseSpeedSum += speed;
                            phaseSpeedCount++;
                            
                            // 3. Search extreme
                            if (direction == 0) {
                                direction = (currDist > prevDist) ? 1 : -1;
                            } else if ((direction == 1 && currDist < prevDist) || 
                                       (direction == -1 && currDist > prevDist)) {
                                
                                if (lastExtremeDist != -1) {
                                    int a = (prevDist + lastExtremeDist) / 2;
                                    double e_squared = ECCENTRICITY * ECCENTRICITY;
                                    double b = a * Math.sqrt(1 - e_squared);
                                    double area = Math.PI * a * b;
                                    String extremeDate = setFormatDate(prevLine);
                                    if (extremeDate != null) {
                                        areaByDate.put(extremeDate, area);
                                        phaseSpeeds.put(extremeDate, phaseSpeedSum / phaseSpeedCount);
                                        listDates.add(extremeDate);
                                    }
                                }
                                lastExtremeDist = prevDist;
                                phaseSpeedSum = 0;
                                phaseSpeedCount = 0;
                                direction = (currDist > prevDist) ? 1 : -1;
                            }
                        }
                        prevLine = line;
                        prevDist = currDist;
                    }
                }
            }
            // write last day
            if (currentDay != null && dailyCount > 0) {
                dailySpeeds.put(currentDay, dailySpeedSum / dailyCount);
                dailyDistance.put(currentDay, dailyDistanceSum / dailyCount);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private String setFormatDate(String line) {
        String cleanDate = extractDate(line);
        if (cleanDate == null) return null;
        String day = cleanDate.substring(0, 2);
        String month = cleanDate.substring(3, 6);
        String year = cleanDate.substring(7, 11);
        String num = "01";
        
        switch (month) {
            case "Jan": num = "01"; break; case "Feb": num = "02"; break;
            case "Mar": num = "03"; break; case "Apr": num = "04"; break;
            case "May": num = "05"; break; case "Jun": num = "06"; break;
            case "Jul": num = "07"; break; case "Aug": num = "08"; break;
            case "Sep": num = "09"; break; case "Oct": num = "10"; break;
            case "Nov": num = "11"; break; case "Dec": num = "12"; break;
        }
        return year + "-" + num + "-" + day;
    }

    private String extractDate(String line) {
        if (line.contains("\"time\"")) {
            int startIndex = line.indexOf("\"time\":\"") + 8;
            return line.substring(startIndex, startIndex + 11);
        } else if (line.length() >= 11) {
            return line.substring(0, 11);
        }
        return null;
    }
    
    private int extractDistance(String line) {
        if (line.contains("\"distance\"")) {
            Matcher matcher = DISTANCE_PATTERN.matcher(line);
            if (matcher.find()) return Integer.parseInt(matcher.group(1));
        } else if (line.length() > 50) {
            try { return Integer.parseInt(line.substring(45, 51)); } catch (NumberFormatException e) {}
        }
        return -1;
    }

    private void sortDates(ArrayList<String> list){
        String tmp;
        for(int i = list.size()-1; i >=0 ; i--){
            for(int j = 0; j < i; j++){
                if(list.get(j).compareTo(list.get(j+1))>0){
                    tmp = list.get(j);
                    list.set(j, list.get(j+1));
                    list.set(j+1, tmp);
                }
            }
        }
    }
}