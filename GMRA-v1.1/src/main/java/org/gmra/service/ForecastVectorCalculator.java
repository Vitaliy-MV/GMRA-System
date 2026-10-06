package org.gmra.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.gmra.dto.PredictionResult;
import org.springframework.stereotype.Service;

@Service
public class ForecastVectorCalculator {

    private final PredictionCalculator res;
    
    public ForecastVectorCalculator(PredictionCalculator predictionResult) {
        this.res = predictionResult;
    }
	
public PredictionResult calculateTrend(List<Map<String, Object>> allData, LocalDate baseDate, LocalDate forecastDate, int daysWindow)  {
        PredictionResult forecastToday = res.calculateProbability(allData, baseDate, forecastDate, daysWindow);
        if (forecastToday == null) return null;
     
        double[] day = new double[3];
        for (int i = 0; i <= day.length-1; i++) {
            PredictionResult pastDay = res.calculateProbability(allData,
                    baseDate.minusDays(i), baseDate.minusDays(i), daysWindow);
            day[i] = (pastDay != null) ? pastDay.probabilityPercent : 0.0;
        }
        boolean trendUp = true;
        boolean trendDown = true;
        for (int x = 0; x < day.length - 1; x++) {
            if (day[x] <= day[x+1]-5 && day[x] <= day[x+1]+5) trendUp = false;
            if (day[x] >= day[x+1]-5 && day[x] >= day[x+1]+5) trendDown = false;
        }
        double currentPercent = forecastToday.probabilityPercent;

        // 🛑 Post-Seismic Discharge
        // =========================
        int baseIndex = -1;
        for (int i = 0; i < allData.size(); i++) {
            LocalDate rowDate = ((java.sql.Date) allData.get(i).get("stat_date")).toLocalDate();
            if (rowDate.equals(baseDate)) {
                baseIndex = i;
                break;
            }
        }
        double yesterdayMaxMag = 0.0;
        int yesterdayIndex = Math.max(0, baseIndex - 1);
        if (yesterdayIndex >= 0) {
            Object magObj = allData.get(yesterdayIndex).get("max_magnitude");
            if (magObj != null) yesterdayMaxMag = ((Number) magObj).doubleValue();
        }
        if (yesterdayMaxMag >= 6.5)
        	trendUp = false;
        //===================
        
        if (trendUp) {
        	if (currentPercent < 75.0) {
            forecastToday.activeTriggers.add("⬆️ Accumulation Phase (+10%)");
            forecastToday.probabilityPercent = currentPercent + 10;
            }
        	else
        		forecastToday.activeTriggers.add("⬆️ Accumulation Phase");
        }
        else if (trendDown) {
            forecastToday.activeTriggers.add("⬇️ Discharge Phase"); 
        }
        else {
            forecastToday.activeTriggers.add("〽️ Tectonic Fluctuations");
        }
        forecastToday.probabilityPercent = Math.round(forecastToday.probabilityPercent * 100.0) / 100.0;
        return forecastToday;
    }
}
