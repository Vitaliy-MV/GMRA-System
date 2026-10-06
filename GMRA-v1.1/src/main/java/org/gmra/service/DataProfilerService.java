package org.gmra.service;

import org.gmra.dao.SystemDao;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class DataProfilerService {
	
	private final Tools tools;
    private final SystemDao systemDao;
    private final MathCalculationService mathService;
    private final Map<String, List<Double>> distributions = new HashMap<>();
    
    public DataProfilerService (SystemDao systemDao, Tools tools, 
    				MathCalculationService mathCalculationService) {
    	this.tools = tools;
    	this.systemDao = systemDao;
        this.mathService = mathCalculationService;

    }

    public void setProfileCatastrophicEvents(int windowSize, double targetMagnitude) {
        tools.writeLog(String.format("🔍 Starting Data Profiling for M >= %.1f...", targetMagnitude));
        List<Map<String, Object>> allData = systemDao.getAllHistoricalDataJoined();
        if (allData.size() < 10) return;
        
        systemDao.cleanTableEventProfile();
        // Initializing storage for medians
        String[] triggers = {"pearson", "spearman", "benioff_vel", "depth_delta", "b_value", "etas_delta"};
        int[] daysBack = {0, 1, 2, 7};
        for (String t : triggers) {
            for (int d : daysBack) {
                distributions.put(t + "_day_" + d, new ArrayList<>());
            }
        }
        int eventsFound = 0;
        // Starting from 7 days ago
        for (int i = 7 + windowSize; i < allData.size(); i++) {
            Double mag = (Double) allData.get(i).get("max_magnitude");
            if (mag != null && mag >= targetMagnitude) {
                eventsFound++;
                java.sql.Date eventDate = (java.sql.Date) allData.get(i).get("stat_date");
                Map<String, Double> eventMetrics = new HashMap<>();
                for (int d : daysBack) {
                    int targetIndex = i - d;
                    
                    // winToday is the sampling window 3 days/points for correlation of the data.
                    List<Map<String, Object>> windowData = allData.subList(targetIndex - windowSize + 1, targetIndex + 1);
                    double[] winTodayBenioff = mathService.extractDoubleArray(windowData, "benioff_strain");
                    double[] winTodayBValues = mathService.extractDoubleArray(windowData, "b_value");
                    double[] winTodayMag = mathService.extractDoubleArray(windowData, "sum_magnitude");
                    double[] winTodayDepths = mathService.extractDoubleArray(windowData, "avg_depth");
                    double[] winTodayEventCounts = mathService.extractDoubleArray(windowData, "event_count");
                    
                    double benioffVel = mathService.calculateDelta(winTodayBenioff);
                    double bValue = winTodayBValues[winTodayBValues.length-1];
                    double pearson = mathService.getPearsonCorrelation(winTodayMag, winTodayDepths);
                    double spearman = mathService.getSpearmanCorrelation(winTodayMag, winTodayDepths);
                    double depthDelta = mathService.calculateDelta(winTodayDepths);
                    double etas = winTodayEventCounts[winTodayEventCounts.length-1];

                    eventMetrics.put("pearson_day_" + d, pearson);
                    eventMetrics.put("spearman_day_" + d, spearman);
                    eventMetrics.put("benioff_vel_day_" + d, benioffVel);
                    eventMetrics.put("depth_delta_day_" + d, depthDelta);
                    eventMetrics.put("b_value_day_" + d, bValue);
                    eventMetrics.put("etas_delta_day_" + d, etas);

                    // arrays for calculating the median
                    distributions.get("pearson_day_" + d).add(pearson);
                    distributions.get("spearman_day_" + d).add(spearman);
                    distributions.get("benioff_vel_day_" + d).add(benioffVel);
                    distributions.get("depth_delta_day_" + d).add(depthDelta);
                    distributions.get("b_value_day_" + d).add(bValue);
                    distributions.get("etas_delta_day_" + d).add(etas);
                }
                systemDao.saveEventProfile(eventDate, mag, eventMetrics);
            }
        }
        tools.writeLog("✅ Profiled " + eventsFound + " catastrophic events.");

     // CALCULATION AND PREPARATION OF STANDARDS (Baselines)
        List<Object[]> batchArgs = new ArrayList<>();
        for (String trigger : triggers) {
            Map<Integer, Double> means = new HashMap<>();
            Map<Integer, Double> medians = new HashMap<>();
            for (int d : daysBack) {
                List<Double> values = distributions.get(trigger + "_day_" + d);
                means.put(d, calculateMean(values));
                medians.put(d, mathService.calculateMedian(values));
            }
            batchArgs.add(new Object[]{
                trigger,
                means.get(0), medians.get(0),
                means.get(1), medians.get(1),
                means.get(2), medians.get(2),
                means.get(7), medians.get(7)
            });
        }
        systemDao.saveCatastrophicBaselines(batchArgs);
        tools.writeLog("✅ Catastrophic baselines (Means & Medians) successfully calculated and saved.");
    }

    private double calculateMean(List<Double> values) {
        if (values == null || values.isEmpty()) return 0.0;
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }
}