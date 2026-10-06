package org.gmra.service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.gmra.config.BootApplication;
import org.springframework.stereotype.Service;

@Service
public class IscDataCalculator {
    private final static String DIR = BootApplication.DIRSysFiles + "Data ISC/";
    
    // Порог представительности (Magnitude of Completeness)
    private static final double MC_THRESHOLD = 4.0;

    public static class IscResult {
        public final List<String> listDates;
        public final Map<String, Double> depthByDate;
        public final Map<String, Double> magnitudeByDate;
        public final Map<String, Double> maxMagnitudeByDate;
        public final Map<String, Integer> countByDate;
        public final Map<String, Double> bValueByDate;
        public final Map<String, Double> benioffByDate;

        public IscResult(List<String> listDates, 
                Map<String, Double> depthByDate, Map<String, Double> magnitudeByDate,
                Map<String, Double> maxMagnitudeByDate, Map<String, Integer> countByDate,
                Map<String, Double> bValueByDate, Map<String, Double> benioffByDate) {
            this.listDates = listDates;
            this.depthByDate = depthByDate;
            this.magnitudeByDate = magnitudeByDate;
            this.maxMagnitudeByDate = maxMagnitudeByDate;
            this.countByDate = countByDate;
            this.bValueByDate = bValueByDate;
            this.benioffByDate = benioffByDate;
        }
    }

    public IscResult calculateAll() {
        ArrayList<String> listDates = new ArrayList<>();
        Map<String, Double> sumDepth = new HashMap<>();
        Map<String, Double> sumMagnitudeByDate = new HashMap<>();
        Map<String, Double> maxMagnitude = new HashMap<>();
        Map<String, Integer> countByDate = new HashMap<>();
        
        // Collections for b-value (events >= MC_THRESHOLD)
        Map<String, Double> sumMagnitudeAboveMc = new HashMap<>();
        Map<String, Integer> countAboveMc = new HashMap<>();
        
        // Collections Benioff's Energy Release
        Map<String, Double> benioffByDate = new HashMap<>();

        processFiles(listDates, sumDepth, sumMagnitudeByDate, maxMagnitude, 
        		countByDate, sumMagnitudeAboveMc, countAboveMc, benioffByDate);
        sortDates(listDates);

        // Final maps for average values and b-value
        Map<String, Double> avgDepthByDate = new HashMap<>();
        Map<String, Double> bValueByDate = new HashMap<>();

        for (String date : listDates) {
            // average Depth
            int count = countByDate.get(date);
            if (count > 0) {
                avgDepthByDate.put(date, sumDepth.get(date) / count);
            }

            // Calculating b-value by Aki-Utsu method
            int countMc = countAboveMc.getOrDefault(date, 0);
            double sumMc = sumMagnitudeAboveMc.getOrDefault(date, 0.0);
 
            if (countMc >= 3) { // Minimum 3 events >= 4.0
                double avgMagnitudeMc = sumMc / countMc;
                if (avgMagnitudeMc > MC_THRESHOLD) {
                    double b = Math.log10(Math.E) / (avgMagnitudeMc - MC_THRESHOLD);
                    bValueByDate.put(date, b);
                }
                else { // Default norm
                    bValueByDate.put(date, 1.0); 
                }
            } 
            else { // if countMc < 3 background normal
                bValueByDate.put(date, 1.0);
            }
        }
        
        return new IscResult(listDates, avgDepthByDate, sumMagnitudeByDate,
        					maxMagnitude, countByDate, bValueByDate, benioffByDate);
    }

    private void processFiles(List<String> listDates,
            Map<String, Double> sumDepth, Map<String, Double> sumMagnitude,
            Map<String, Double> maxMagnitude,  Map<String, Integer> countByDate,
            Map<String, Double> sumMagnitudeAboveMc, Map<String, Integer> countAboveMc,
            Map<String, Double> benioffByDate) {
        
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
                    Iterator<String> it = allDataLines.iterator();
                    while (it.hasNext()) {
                        String line = it.next();
                        if (line.isEmpty() || line.startsWith("#") ||
                            line.startsWith("-") || line.startsWith("EVENTID")) {
                            continue;
                        }
                        
                        String dateFull = "";
                        String depthStr = "";
                        String magnitudeStr = "";

                        try {
                            // FDSNWS Client
                            if (line.contains("|")) {
                                String[] data = line.split("\\|");
                                if (data.length >= 11) {
                                    dateFull = data[1].trim();
                                    depthStr = data[4].trim();
                                    magnitudeStr = data[10].trim();
                                } else continue;
                            } 
                            // CSV Manual
                            else if (line.contains(",")) {
                                String[] data = line.split(",");
                                if (data.length >= 12) {
                                    dateFull = data[3].trim();
                                    depthStr = data[7].trim();
                                    magnitudeStr = data[11].trim();
                                } else continue;
                            } 
                            else {
                                continue; // unknown format
                            }
                            // Date: YYYY-MM-DD
                            String date = dateFull.length() >= 10 ? dateFull.substring(0, 10) : dateFull;
                            double depth = depthStr.isEmpty() ? 0.0 : Double.parseDouble(depthStr);
                            double magnitude = magnitudeStr.isEmpty() ? 0.0 : Double.parseDouble(magnitudeStr);
                            
                            // add day if not exist
                            if (!countByDate.containsKey(date)) {
                                listDates.add(date);
                                countByDate.put(date, 0);
                                sumDepth.put(date, 0.0);
                                sumMagnitude.put(date, 0.0);
                                maxMagnitude.put(date, 0.0);
                                sumMagnitudeAboveMc.put(date, 0.0);
                                countAboveMc.put(date, 0);
                                benioffByDate.put(date, 0.0);
                            }

                            // --- РАСЧЕТ ЭНЕРГИИ БЕНИОФФА ДЛЯ ТЕКУЩЕГО ТОЛЧКА ---
                            if (magnitude > 0) {
                                // E = 10^(1.5 * M + 4.8)
                                double energy = Math.pow(10, 1.5 * magnitude + 4.8);
                                double strain = Math.sqrt(energy);
                                // Добавляем к сумме за день
                                benioffByDate.put(date, benioffByDate.get(date) + strain); 
                            }
                            
                            // Aggregating all data
                            countByDate.put(date, countByDate.get(date) + 1);
                            sumDepth.put(date, sumDepth.get(date) + depth);
                            sumMagnitude.put(date, sumMagnitude.get(date) + magnitude);
                            if (magnitude > maxMagnitude.get(date)) {
                                maxMagnitude.put(date, magnitude);
                            }
                            // Агрегируем данные только для крупных землетрясений (b-value)
                            if (magnitude >= MC_THRESHOLD) {
                                sumMagnitudeAboveMc.put(date, sumMagnitudeAboveMc.get(date) + magnitude);
                                countAboveMc.put(date, countAboveMc.get(date) + 1);
                            }
                        } catch (Exception e) {/*Ignore parse errors*/}
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
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