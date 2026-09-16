package org.gmra.service;

import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.apache.commons.math3.stat.correlation.SpearmansCorrelation;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.gmra.dao.SystemDao;
import org.gmra.dto.ScoringWeights;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class MathCalculationService {

    private final Tools tools;
    private final SystemDao systemDao;
    public ScoringWeights cachedWeights;
    private List<Map<String, Object>> cachedAstroDates;
    public final Map<String, Double> baselineSigma = new HashMap<>();
    private final AtomicBoolean isSystemReady = new AtomicBoolean(false);

    public MathCalculationService(Tools tools, SystemDao systemDao) {
    	this.systemDao = systemDao;
        this.tools = tools;
    }
    
    public boolean isSystemReady() {
        return isSystemReady.get();
    }
    public void setSystemReady(boolean ready) {
        this.isSystemReady.set(ready);
    }
    public void loadAstroDatesToCache() {
        cachedAstroDates = systemDao.getAllAstronomyDates();
    }
    public void loadSigmaToCache(String paramName, double sigma) {
    	baselineSigma.put(paramName, sigma);
    }
    
    public long getDaysToNearestApside(LocalDate targetDate) {
        long minDays = Long.MAX_VALUE;
        for (Map<String, Object> row : cachedAstroDates) {
            LocalDate perihelion = ((java.sql.Date) row.get("perihelion")).toLocalDate();
            LocalDate aphelion = ((java.sql.Date) row.get("aphelion")).toLocalDate();
            long daysToPer = Math.abs(ChronoUnit.DAYS.between(targetDate, perihelion));
            long daysToAph = Math.abs(ChronoUnit.DAYS.between(targetDate, aphelion));
            minDays = Math.min(minDays, Math.min(daysToPer, daysToAph));
        }
        return minDays;
    }

    public long getDaysToNearestEquinox(LocalDate targetDate) {
        long minDays = Long.MAX_VALUE;
        for (Map<String, Object> row : cachedAstroDates) {
            LocalDate springEq = ((java.sql.Date) row.get("spring_eq")).toLocalDate();
            LocalDate autumnEq = ((java.sql.Date) row.get("autumn_eq")).toLocalDate();
            long daysToSpring = Math.abs(ChronoUnit.DAYS.between(targetDate, springEq));
            long daysToAutumn = Math.abs(ChronoUnit.DAYS.between(targetDate, autumnEq));
            minDays = Math.min(minDays, Math.min(daysToSpring, daysToAutumn));
        }
        return minDays;
    }   
    public double getPearsonCorrelation(double[] arrayX, double[] arrayY) {
        if (arrayX == null || arrayY == null || arrayX.length < 2 || arrayX.length != arrayY.length) {
            return 0.0;
        }
        double correlation = new PearsonsCorrelation().correlation(arrayX, arrayY);
        return Double.isNaN(correlation) ? 0.0 : correlation;
    }

    public double getSpearmanCorrelation(double[] arrayX, double[] arrayY) {
        if (arrayX == null || arrayY == null || arrayX.length < 2 || arrayX.length != arrayY.length) {
            return 0.0;
        }
        double correlation = new SpearmansCorrelation().correlation(arrayX, arrayY);
        return Double.isNaN(correlation) ? 0.0 : correlation;
    }

    public void setScoringConfig() {
        tools.writeLog("Starting scoring calibration (Backtesting)...");
        List<Map<String, Object>> allData = systemDao.getAllHistoricalDataJoined();
        if (allData.size() < 15) return;
        int windowSize = 3; // days 
        double Mp = 6.9; 	// search magnitude parameter
        
        // Score count
        int totalDisasters = 0;
        int hitsAphelion = 0;
        int hitsEquinox = 0;
        int hitsPearsonMD = 0;
        int hitsSpearmanMD = 0;
        int hitsMoonAreaPhase = 0;
        int hitsMomentumExt = 0;
        int hitsSpeedMoonExt = 0;
        int hitsDepthAnomaly = 0;
        int hitsBValueAnomaly = 0;
        int hitsEnergyAcceleration = 0;
        int hitsEventSwarm = 0;
        int hitsSeismicQuiescence = 0;


        // Parsing history and look for catastrophes
        for (int i = windowSize; i < allData.size(); i++) {
            Map<String, Object> currentDay = allData.get(i);
            Double magnitude = (Double) currentDay.get("max_magnitude");
            
            // If magnitude on this day < Mp parameter
            if (magnitude == null || magnitude < Mp) continue;
            
            totalDisasters++; // found  > Mp parameter
            // Take a windowSize (3 days) BEFORE this disaster
            List<Map<String, Object>> windowData = allData.subList(i - windowSize, i);
            
            // Array data for windowSize
            double[] windowBenioff = extractDoubleArray(windowData, "benioff_strain");
            double[] windowMagnitudes = extractDoubleArray(windowData, "sum_magnitude");
            double[] windowDepths = extractDoubleArray(windowData, "avg_depth");
            double[] windowBValues = extractDoubleArray(windowData, "b_value");
            double[] windowEventCounts = extractDoubleArray(windowData, "event_count");
            LocalDate disasterDate = ((java.sql.Date) currentDay.get("stat_date")).toLocalDate();  
            /** --- H Y P O T H E S I S   T E S T I N G   ( L I S T   O F   R U L E S ) --- **/
            
         // 1. Earth near Aphelion / Perihelion (<= 28 days +7)
         if (getDaysToNearestApside(disasterDate) <= 35) {
             hitsAphelion++;
         }
         // 2. Earth near Equinox (<= 16 days / half-moon rotation Max.)
         if (getDaysToNearestEquinox(disasterDate) <= 16) {
             hitsEquinox++;
         }
         // 3. Phase change of the Moon's orbital area
         boolean windowAreaPhase = isMoonAreaPhaseShift(allData, i, windowSize);
         if (windowAreaPhase) {
             hitsMoonAreaPhase++;
         }
         // 4. Momentum Derivatives are right at their peak
         if (isSpeedDerivativeExtremum(allData, i)) {
             hitsMomentumExt++;
         }
         // 5. Moon speed <= 3 days from extreme
         int days = isNearMoonSpeedExtremumGradient(allData, i);
         if (days<=3 && days>=0) {
        	 hitsSpeedMoonExt++;
         }
         
         /** SEISMIC CORRELATIONS **/
         //  6. Pearson & 7. Spearman
         // =========================
         int yesterdayStart = i - windowSize - 1;
         int yesterdayEnd = i - 1; // Yesterday's full-size backtesting window        
         double pearsonYesterday = Math.abs(getPearsonCorrelation(windowMagnitudes, windowDepths));
         double spearmanYesterday = Math.abs(getSpearmanCorrelation(windowMagnitudes, windowDepths));
         
         if (yesterdayStart >= 0 && windowSize >= 3) {
             List<Map<String, Object>> windowDataYesterday = allData.subList(yesterdayStart, yesterdayEnd);
             double[] windowMagYesterday = extractDoubleArray(windowDataYesterday, "sum_magnitude");
             double[] windowDepthYesterday = extractDoubleArray(windowDataYesterday, "avg_depth");
             
             if (windowMagYesterday.length >= 3) {
                 pearsonYesterday = Math.abs(getPearsonCorrelation(windowMagYesterday, windowDepthYesterday));
                 spearmanYesterday = Math.abs(getSpearmanCorrelation(windowMagYesterday, windowDepthYesterday));
             }
         }
         // Pearson (Magnitude -vs- Depth)
         double pearsonToday = Math.abs(getPearsonCorrelation(windowMagnitudes, windowDepths));
         double pearsonGradient = pearsonToday - pearsonYesterday;
         
         if (pearsonToday >= 0.85 && Math.abs(pearsonGradient) >= 0.1) {
             hitsPearsonMD += 2; // Unidirectional
         } else if (pearsonToday >= 0.85) {
             hitsPearsonMD++;
         } else if (pearsonToday >= 0.75) {
             hitsPearsonMD += 0.75; 
         }
         // Spearman (Magnitude -vs- Depth)
         double spearmanToday = Math.abs(getSpearmanCorrelation(windowMagnitudes, windowDepths));
         double spearmanGradient = spearmanToday - spearmanYesterday;
         
         if (spearmanToday >= 0.85 && Math.abs(spearmanGradient) >= 0.1) {
             hitsSpearmanMD += 2; // Unidirectional
         } else if (spearmanToday >= 0.85) {
             hitsSpearmanMD++;
         } else if (spearmanToday >= 0.75) {
             hitsSpearmanMD += 0.75;
         }
         
         // 8, 9. Benioff's energy acceleration trend
         // =========================================
         double[] velocityBenioff = calculateDerivative(windowBenioff);
         if (velocityBenioff.length > 0) {
             double currentEnergySpeed = velocityBenioff[velocityBenioff.length - 1];
             
             // Energy decreases after a peak
             if (isEnergyQuiescenceAfterPeak(windowBenioff)) {
            	 hitsSeismicQuiescence++;
             } 
             // Energy continues increase
             else if (currentEnergySpeed > baselineSigma.get("benioff_strain")) {
            	 hitsEnergyAcceleration++;
             }
         }
         // 10. Depth anomaly
         // =================
         if (windowDepths.length >= 2) {
             double depthToday = windowDepths[windowDepths.length - 1];
             double depthYesterday = windowDepths[windowDepths.length - 2];
             double depthGradient = depthToday - depthYesterday;
             
             if (depthGradient < 0) { // ONLY if the pressure upwards
                 double normalDepthSigma = baselineSigma.get("global_depth");
                 double depthAccelerationRatio = Math.abs(depthGradient) / normalDepthSigma;
                 
                 if (depthAccelerationRatio > 0.2) {
                     hitsDepthAnomaly++;
                 }
             }
         }
         // 11. b-value anomaly
         // ==================
         if (windowBValues.length > 0) {
             double currentBValue = windowBValues[windowBValues.length - 1];
             double[] velocityB = calculateDerivative(windowBValues);
             double currentBSpeed = velocityB.length > 0 ? velocityB[velocityB.length - 1] : 0.0;
             
             if (currentBValue < 1.0 && currentBValue >= 0.5) {
                 hitsBValueAnomaly++;
             }
             else if (currentBValue > 1.0 && currentBValue <= 1.3 && currentBSpeed < 0) {
                 hitsBValueAnomaly++;
             }
         }
         // 12. ETAS Seismic Swarm
         // ================================
         /**	---- Two-level ----		 **/ 
         /** ---- linear regression ---- **/
         int macroStart = Math.max(0, i - 6); // Subwindow size 
         List<Map<String, Object>> macroWindowData = allData.subList(macroStart, i);
         double[] macroEventCounts = extractDoubleArray(macroWindowData, "event_count");
         if (windowEventCounts.length >= 2 && macroEventCounts.length >= 3) {
             double currentCount = windowEventCounts[windowEventCounts.length - 1];
             double currentEventSpeed = currentCount - windowEventCounts[windowEventCounts.length - 2];
             double overallTrend = calculateTrendSlope(macroEventCounts);
             double normalEventCountSigma = baselineSigma.get("event_count");
             
             if (currentEventSpeed > 0) {
                 double swarmAccelerationRatio = currentEventSpeed / normalEventCountSigma;
                 boolean isSustainedSwarm = overallTrend > 0;
                 
                 if (swarmAccelerationRatio > 0.15 || (swarmAccelerationRatio > 0.08 && isSustainedSwarm)) {
                     hitsEventSwarm++;
                 }
             } 
             // Seismic silence / Lock-in
             else if (currentEventSpeed <= 0) {
                 double dropRatio = Math.abs(currentEventSpeed) / normalEventCountSigma;
                 if (overallTrend > 0 || dropRatio > 0.07) {
                     hitsEventSwarm++;
                 }
             }
         }
  }
  if (totalDisasters == 0) {
            tools.writeLog("No data found; calibration cancelled.");
            return;
        }
        
        /** C A L C U L A T E   W E I G H T S   (ScoringWeights) **/
  		// Calculate percentage of the "hits" (from 0.0 to 1.0)
        ScoringWeights calibratedWeights = new ScoringWeights();
        double rateAphelion = (double) hitsAphelion / totalDisasters;
        double rateEquinox = (double) hitsEquinox / totalDisasters;
        double ratePearsonMD = (double) hitsPearsonMD / totalDisasters;
        double rateSpearmanMD = (double) hitsSpearmanMD / totalDisasters;
        double rateMoonAreaPhase = (double) hitsMoonAreaPhase / totalDisasters;
        double rateMomentumExt = (double) hitsMomentumExt / totalDisasters;
        double rateSpeedMoonExt = (double) hitsSpeedMoonExt / totalDisasters;
        double rateDepthAnomaly = (double) hitsDepthAnomaly / totalDisasters;
        double rateBValueAnomaly = (double) hitsBValueAnomaly / totalDisasters;
        double rateEnergyAcceleration = (double) hitsEnergyAcceleration / totalDisasters;
        double ratetEventSwarm = (double) hitsEventSwarm / totalDisasters;
        double rateSeismicQuiescence = (double) hitsSeismicQuiescence / totalDisasters;

        // Multiply the base maximum score by the success percentage
        calibratedWeights.weightAphelion *= rateAphelion;
        calibratedWeights.weightEquinox *= rateEquinox;
        calibratedWeights.weightPearsonMD *= ratePearsonMD;
        calibratedWeights.weightSpearmanMD *= rateSpearmanMD;
        calibratedWeights.weightMoonAreaPhase *= rateMoonAreaPhase;
        calibratedWeights.weightMomentumExt *= rateMomentumExt;
        calibratedWeights.weightSpeedMoonExt *= rateSpeedMoonExt;
        calibratedWeights.weightDepthAnomaly *= rateDepthAnomaly;
        calibratedWeights.weightBValueAnomaly *= rateBValueAnomaly; 
        calibratedWeights.weightEnergyAcceleration *= rateEnergyAcceleration; 
        calibratedWeights.weightEventSwarm *= ratetEventSwarm; 
        calibratedWeights.weightSeismicQuiescence *= rateSeismicQuiescence; 

        systemDao.updateScoringWeights(calibratedWeights);
        this.cachedWeights = calibratedWeights;

        tools.writeLog(String.format("Calibration completed on %d events.", totalDisasters));
        tools.writeLog(String.format("Depth Anomaly weight is now %.2f", calibratedWeights.weightDepthAnomaly));
    }

    /**
     * The calculation of the lunar orbit area follows the sequence: apogee -> perigee -> perigee -> apogee, etc.
     * This method iterates through the entire year to find the date intervals for the lunar orbit extremums:
     * the minimum (e.g., April 14 to May 11 for 2025) and the maximum (e.g., August 14 to September 1 for 2025).
     * If the target date window (standard size is 14 days) falls within this interval for more than half 
     * of its duration (e.g., 8 out of 14 days), the method returns true.
     * 
     * The physical significance of the "Moon's Orbit Area vs. Aphelion/Perihelion" is that the turning point 
     * of the lunar orbit in a three-body system is shifted relative to the Earth's aphelion and perihelion. 
     * The {@code isMoonAreaPhaseShift} method determines the state at the current moment, while the 
     * {@code getDaysToNearestApside} and {@code getDaysToNearestEquinox} methods indicate the current position 
     * relative to the aphelion, perihelion, and the vernal/autumnal equinoxes.
     */
    public boolean isMoonAreaPhaseShift(List<Map<String, Object>> allData, int currentIndex, int daysWindow) {
        if (allData == null || allData.isEmpty() || currentIndex < 0 || currentIndex >= allData.size()) {
            return false;
        }
        LocalDate targetDate = ((java.sql.Date) allData.get(currentIndex).get("stat_date")).toLocalDate();
        int targetYear = targetDate.getYear();
        double maxArea = Double.MIN_VALUE;
        double minArea = Double.MAX_VALUE;
      
        int maxStartIdx = -1, maxEndIdx = -1;
        int minStartIdx = -1, minEndIdx = -1;
     
        int prevRecordIdx = -1;
        // absolute annual extremes and their time intervals
        for (int i = 0; i < allData.size(); i++) {
            Object areaObj = allData.get(i).get("orbit_area");
            if (areaObj != null && ((Number) areaObj).doubleValue() > 0) {
                double area = ((Number) areaObj).doubleValue();
                LocalDate rowDate = ((java.sql.Date) allData.get(i).get("stat_date")).toLocalDate();
                
                if (rowDate.getYear() == targetYear) {
                    // annual maximum
                    if (area > maxArea) {
                        maxArea = area;
                        maxEndIdx = i;
                        // Interval start = day after previous entry (or current day if there are no previous entries)
                        maxStartIdx = (prevRecordIdx != -1) ? prevRecordIdx + 1 : i;
                    }
                    // annual minimum
                    if (area < minArea) {
                        minArea = area;
                        minEndIdx = i;
                        minStartIdx = (prevRecordIdx != -1) ? prevRecordIdx + 1 : i;
                    }
                }
                prevRecordIdx = i; 
            }
        }
        // not enough data for this year
        if (maxEndIdx == -1 || minEndIdx == -1) {
            return false;
        }
        // Create a centered window for astronomical data (forward and backward)
        /** Centered window (forward and backward):	 int halfWindow = daysWindow / 2;
			retrospective window (looking backward): int halfWindow = daysWindow;  */
        daysWindow = 30;
        int halfWindow = daysWindow / 2; // cycle of motion from perigee to apogee
        int windowStart = Math.max(0, currentIndex - halfWindow);
        int windowEnd = Math.min(allData.size() - 1, windowStart + daysWindow);
        if (windowStart > windowEnd) return false;

        // Check if the window dates fall 
        // within the annual extremum intervals
        int matchCount = 0;
        int totalWindowDays = windowEnd - windowStart + 1;
        for (int i = windowStart; i <= windowEnd; i++) {
            boolean inMaxInterval = (i >= maxStartIdx && i <= maxEndIdx);
            boolean inMinInterval = (i >= minStartIdx && i <= minEndIdx);
            if (inMaxInterval || inMinInterval) {
                matchCount++;
            }
        }
        // Return true if the majority of the window 
        // (more than 50%) falls within the extremum
        int requiredOverlap = Math.min(totalWindowDays / 2, 7);
        return matchCount > requiredOverlap;
    }
    
    public double[] extractDoubleArray(List<Map<String, Object>> data, String columnName) {
        double[] arr = new double[data.size()];
        for (int i = 0; i < data.size(); i++) {
            Object val = data.get(i).get(columnName);
            arr[i] = val != null ? ((Number) val).doubleValue() : 0.0;
        }
        return arr;
    }
    public List<Double> extractList(double[] arr) {
        List<Double> list = new ArrayList<>(arr.length);
        for (double v : arr) list.add(v);
        return list;
    }
    
	/** The method checks deviation from the average sigma:
	 *  @param NAME: moon_speed, moon_speed_derivative 
	 *  global_depth, global_magnitude **/ 
	public double calculateSigma(String paramName, List<Double> dataPoints) {
		if (dataPoints == null || dataPoints.isEmpty()) return 0.0;
		DescriptiveStatistics stats = new DescriptiveStatistics();
		for (Double val : dataPoints) {
			if (val != null && !Double.isNaN(val)) {
	                stats.addValue(val);
	            }
	        }
	        double currentWindowSigma = stats.getStandardDeviation(); 
	        Double normalSigma = baselineSigma.get(paramName);
	        if (normalSigma == null) return 0.0;
			return Math.abs(currentWindowSigma - normalSigma);
	    }
	
	public boolean isSpeedDerivativeExtremum(List<Map<String, Object>> allData, int currentIndex) {
        return isExtremum(allData, currentIndex, "speed_derivative");
    }
    public boolean isNearMoonSpeedExtremum(List<Map<String, Object>> allData, int currentIndex) {
        int radius = 3;
        int start = Math.max(1, currentIndex - radius);
        int end = Math.min(allData.size() - radius, currentIndex + radius);
        for (int j = start; j <= end; j++) {
            if (isExtremum(allData, j, "avg_speed")) {
                return true;
            }
        }
        return false;
    }
    
    public int isNearMoonSpeedExtremumGradient(List<Map<String, Object>> allData, int currentIndex) {
    	int radius = 3; int count = -1;
        int start = Math.max(1, currentIndex - radius);
        int end = Math.min(allData.size() - radius, currentIndex + radius);
        for (int j = start; j <= end; j++) {
        	count++;
            if (isExtremum(allData, j, "avg_speed")) {
                return count;
            }
        }
        return count;
    }
    
    private boolean isExtremum(List<Map<String, Object>> allData, int index, String columnName) {
        if (index <= 0 || index >= allData.size() - 1) return false;
        double prev = getDouble(allData.get(index - 1), columnName);
        double curr = getDouble(allData.get(index), columnName);
        double next = getDouble(allData.get(index + 1), columnName);
        boolean localMaximum = (curr > prev && curr > next);
        boolean localMinimum = (curr < prev && curr < next);
        return localMaximum || localMinimum;
    }
    private double getDouble(Map<String, Object> row, String columnName) {
        Object val = row.get(columnName);
        return val != null ? ((Number) val).doubleValue() : 0.0;
    }
    
    /** Calculating the first derivative 
	 * the rate of change a metric from day to day */
    public double[] calculateDerivative(double[] data) {
        if (data == null || data.length < 2) return new double[0];
        double[] derivative = new double[data.length - 1];
        for (int i = 0; i < data.length - 1; i++) {
            derivative[i] = data[i + 1] - data[i]; // Delta in 1 day
        }
        return derivative;
    }
    
    public double calculateTrendSlope(double[] data) {
        if (data == null || data.length < 2) return 0.0;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        int n = data.length;
        
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += data[i];
            sumXY += (i * data[i]);
            sumX2 += (i * i);
        }
        // Positive if the trend is up, a negative if the trend is down.
        return (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
    }

    // Search for an extreme (sudden stop of energy release)
    public boolean isEnergyQuiescenceAfterPeak(double[] windowData) {
        double[] velocity = calculateDerivative(windowData);
        if (velocity.length < 2) return false;
        
        double currentVelocity = velocity[velocity.length - 1];
        double previousVelocity = velocity[velocity.length - 2];
        
        // Returns true if energy was actively growing yesterday
        return previousVelocity > 0 && currentVelocity <= 0;
    }
}