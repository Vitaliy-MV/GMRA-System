package org.gmra.service;

import org.gmra.dao.SystemDao;
import org.gmra.dto.PredictionResult;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ForecastOrchestratorService {

    private final Tools tools;
    private final SystemDao systemDao;
    private final PredictionCalculator prediction;
    private final MlPredictionService mlService;
    private final ForecastVectorCalculator forecastVector;

    public ForecastOrchestratorService(Tools tools, SystemDao systemDao, MathCalculationService mathCalculationService,
            ForecastVectorCalculator calculateProbability, PredictionCalculator prediction,
            MlPredictionService mlService) {

        this.tools = tools;
        this.systemDao = systemDao;
        this.prediction = prediction;
        this.mlService = mlService;
        this.forecastVector = calculateProbability;
    }

    public Map<String, PredictionResult> generateFullForecast(LocalDate baseDate, List<Map<String, Object>> allDataFromTest) {
        Map<String, PredictionResult> forecasts = new HashMap<>();
        try {
        	List<Map<String, Object>> allData = allDataFromTest==null ? systemDao.getAllHistoricalDataJoined() : allDataFromTest;
            PredictionResult today = forecastVector.calculateTrend(allData, baseDate, baseDate, 3);
            PredictionResult tomorrow = prediction.calculateProbability(allData, baseDate, baseDate.plusDays(1), 7, false);
            PredictionResult days7 = prediction.calculateProbability(allData, baseDate, baseDate.plusDays(7), 14, false);

            int baseIndex = -1;
            for (int i = 0; i < allData.size(); i++) {
                LocalDate rowDate = ((java.sql.Date) allData.get(i).get("stat_date")).toLocalDate();
                if (rowDate.equals(baseDate)) {
                    baseIndex = i;
                    break;
                }
            }
            // Information radar for M6.0+ (Cascade ML)
            int yesterdayIndex = Math.max(0, baseIndex - 1);
            MlPredictionService.MlForecast ml72h = mlService.getPredictionFor72h(allData, yesterdayIndex);
            MlPredictionService.MlForecast ml48h = mlService.getPredictionFor48h(allData, yesterdayIndex);
            MlPredictionService.MlForecast ml24h = mlService.getPredictionFor24h(allData, yesterdayIndex);
            
            int mlAlarmsToday = (ml24h.isAlarm ? 1 : 0) + (ml48h.isAlarm ? 1 : 0) + (ml72h.isAlarm ? 1 : 0);
            boolean control48Сheck72 = (ml48h.isAlarm && ml72h.isAlarm) ? true : false;
            
            // ========================================
            // ENSEMBLE LOGIC (CASCADE CROSS-VALIDATION)
            // =========================================
            
            /****************************
             *  Yellow zone: 0 - 55%	*
             *  Orange zone: 55 - 75%	*
             *  Red zone: 75 - 100%		*
             ***************************/
            
            // ==== TODAY ======
            if (today != null) {
                double percent = 0.0;
                boolean triggersAdd = false;
                double currPerToday = today.probabilityPercent;
                
                // =============================
                // 🛑 1. Aftershock Filter M6.5+
                // =============================
                double yesterdayMaxMag = 0.0;
                if (yesterdayIndex >= 0) {
                    Object magObj = allData.get(yesterdayIndex).get("max_magnitude");
                    if (magObj != null) yesterdayMaxMag = ((Number) magObj).doubleValue();
                }
                
                if (yesterdayMaxMag >= 7.5) {
                    ml72h.isAlarm = false; 
                    ml48h.isAlarm = false; 
                    ml24h.isAlarm = false; 

                    // Drop into the orange zone
                    if (currPerToday >= 75.0) {
                        percent = currPerToday;
                        double diff = percent - 75;
                        percent = currPerToday - diff - 0.1;
                        percent = Math.round(percent * 100.0) / 100.0;
                        today.probabilityPercent = percent;
                        today.activeTriggers.add("📉 Post-Seismic Discharge (-" + ((int)diff + 0.1) + "%); ML Ignored ⚠️");
                        triggersAdd = true;
                    }
                }
                // ===============================
                // 🛑 2. PESSIMIZATION (0)
                // ===============================
                else if (mlAlarmsToday == 0) {
                    // If the AI ​​is silent or doubtful
                    if (currPerToday >= 75.0) {
                        percent = currPerToday;
                        double diff = percent - 75;
                        percent = currPerToday - diff - 0.1;
                        percent = Math.round(percent * 100.0) / 100.0;
                        today.probabilityPercent = percent;
                        today.activeTriggers.add("3ML 🤖 Not confirmed (-" + ((int)diff + 0.1) + "%)");
                        triggersAdd = true;
                    }
                }
                // ===========================
                // ⚖️ 3. STRICT CASCADE (2 ML)
                // ===========================
                else if (mlAlarmsToday == 2) {
                        if (currPerToday >= 49.0 && currPerToday < 75.0) {
                        	percent = currPerToday;
                            double diff = 75 - percent;
                            percent = currPerToday + (diff + 5.0);
                            percent = Math.round(percent * 100.0) / 100.0;
                            today.probabilityPercent = percent;
                            today.activeTriggers.addAll(ml48h.triggers);
                            today.activeTriggers.add("⚠️ 2ML CASCADE (+" + ((int)diff + 5.0) + "%)");
                            triggersAdd = true;
                        } 
                        else if (currPerToday >= 75.0 && control48Сheck72) {
                            today.probabilityPercent = Math.min(99.0, Math.round((currPerToday + 3.0) * 100.0) / 100.0);
                            today.activeTriggers.addAll(ml48h.triggers);
                            today.activeTriggers.add("🚨 2ML Confirmed! (+3%)");
                            triggersAdd = true;
                        }
                	}
                // ============================
                // 🚀 4. PERFECT STORM (3 ML)
                // ==========================
                else if (mlAlarmsToday == 3) {
                    if (currPerToday < 75.0) {
                    	percent = currPerToday;
                        double diff = 75 - percent;
                        percent = currPerToday + (diff + 0.1);
                        percent = Math.round(percent * 100.0) / 100.0;
                        today.probabilityPercent = percent;
                        today.activeTriggers.addAll(ml24h.triggers);
                        today.activeTriggers.add("⚠️ 3ML CASCADE (+" + ((int)diff + 0.1) + "%)");
                        triggersAdd = true;
                    }
                    else if (currPerToday >= 75.0) {
                        today.probabilityPercent = Math.min(99.0, Math.round((currPerToday + 3.0) * 100.0) / 100.0);
                        today.activeTriggers.addAll(ml24h.triggers);
                        today.activeTriggers.add("🚨 3ML Confirmed! (+3%)");
                        triggersAdd = true;
                    }
                }
                if (!triggersAdd && mlAlarmsToday > 0) {
                 	if (ml24h.isAlarm) today.activeTriggers.addAll(ml24h.triggers);
                 	else if (ml48h.isAlarm)	{
                 		today.activeTriggers.add("== ml48h == >"); 
                 		today.activeTriggers.addAll(ml48h.triggers);
                 	}
                 	else if (ml72h.isAlarm)	{
                 		today.activeTriggers.add("== ml72h == >"); 
                 		today.activeTriggers.addAll(ml72h.triggers);
                 	}
                }
                // ======================
                // ⚡ DYNAMIC PROBABILITY 
                // ======================
                if (today.probabilityPercent < 75.0) {
                	applyDynamicFallback(today, ml24h, ml48h, ml72h, mlAlarmsToday, currPerToday);
                }
                // ====================================
                // 🛡️ SECOND LEVEL OF VERIFICATION (FP)
                // ====================================
                if (today.probabilityPercent >= 75.0) {
                    boolean revertToOrange = false;
                    if (ml72h.probability < 96.0 || ml48h.probability < 96.0) {
                    	revertToOrange = true;
                    }
                    if (revertToOrange) {
                    	percent = currPerToday;
                        double diff = percent - 75;
                        percent = currPerToday - diff - 0.1;
                        percent = Math.round(percent * 100.0) / 100.0;
                        today.probabilityPercent = percent;
                        today.activeTriggers.add("🛡️ Chief Controller (-" + ((int)diff + 0.1) + "%)");
                    }
                }
            }
            //===================
            // === TOMORROW =====
            //===================
            if (tomorrow != null) {
                double currPerTom = tomorrow.probabilityPercent;
                double percent = 0.0;
                if (mlAlarmsToday == 0) {
                    // 3 ML говорят "нет". Уводим в жёлтую зону
                    if (currPerTom > 55) {
                        percent = currPerTom;
                        double diff = percent - 55;
                        percent = currPerTom - diff - 0.1;
                        percent = Math.round(percent * 100.0) / 100.0;
                        tomorrow.probabilityPercent = percent;
                        tomorrow.activeTriggers.add("2ML 🤖 Not confirmed (-" + ((int)diff + 0.1) + "%)");
                    }
                }
                else if (control48Сheck72) {
                    // 2 ML кричат, математика молчит или кричит (Полный CASCADE)
                    if (currPerTom < 75) { // уводим на верх оранжевой зоны 
                        percent = currPerTom;
                        double diff = 75 - percent;
                        percent = currPerTom + (diff - 0.1);
                        percent = Math.round(percent * 100.0) / 100.0;
                        tomorrow.probabilityPercent = percent;
                        tomorrow.activeTriggers.addAll(ml48h.triggers);
                        tomorrow.activeTriggers.add("⚠️ 2ML CASCADE (+" + ((int)diff - 0.1) + "%)");
                    }
                    else if (currPerTom >= 75 && currPerTom <= 85) {
                        tomorrow.probabilityPercent = Math.round((currPerTom + 5.0) * 100.0) / 100.0;
                        tomorrow.activeTriggers.addAll(ml48h.triggers);
                        tomorrow.activeTriggers.add("⚠️ 2ML CASCADE (+5.0%)");
                    } 
                    else if (currPerTom > 85) {
                        tomorrow.activeTriggers.addAll(ml48h.triggers);
                        tomorrow.activeTriggers.add("⚠️ 2ML CASCADE");
                    }
                }
            }

            if (today != null) forecasts.put("today", today);
            if (tomorrow != null) forecasts.put("tomorrow", tomorrow);
            if (days7 != null) forecasts.put("days7", days7);
            
        } catch (Exception e) {
            tools.writeLog("ForecastOrchestratorService: " + e.getMessage());
        }
        return forecasts;
    }
    
    /** Dynamic calculation (50% mathematics + 50% ML) 
     * **********************************************/ 
    private void applyDynamicFallback(PredictionResult today, 
                                          MlPredictionService.MlForecast ml24h, 
                                          MlPredictionService.MlForecast ml48h, 
                                          MlPredictionService.MlForecast ml72h, 
                                          int mlAlarmsToday, double currPerToday) {
        try {
            double avgMlProb = (ml24h.probability + ml48h.probability + ml72h.probability) / 3.0;
            // 1. Normalization contribution of mathematics (0 - 50 points, range 0% - 91.12%)
            double clampedMath = Math.max(0.0, Math.min(91.12, currPerToday));
            double mathShare = 50.0 * ((clampedMath - 0.0) / (91.12 - 0.0));

            // 2. Normalization contribution of ML (0 - 50 points, range 92% - 99%)
            double clampedMl = Math.max(92.0, Math.min(100.0, avgMlProb));
            double mlFactor = 0.5 + 0.5 * ((clampedMl - 92.0) / (100.0 - 92.0));
            
            double mlShare = 0.0;
            if (mlAlarmsToday == 3) {
                mlShare = 50.0 * mlFactor; 
            } else if (mlAlarmsToday == 2) {
                mlShare = 30.0 * mlFactor; 
            } else if (mlAlarmsToday == 1)  {
            	 mlShare = 15.0 * mlFactor; 
            }

            double dynamicProbability = mathShare + mlShare;
            boolean tempAccel = getTempRiver(today.activeTriggers) >= 0.5;
        
            // Additional check: moving to the red zone
            if (dynamicProbability >= 75.0 && mlAlarmsToday == 3 && avgMlProb >= 98.0 && tempAccel) {
                today.activeTriggers.add("⚡ Dynamic Probability ⚡");
            }
            else {
           	 	dynamicProbability = Math.min(dynamicProbability, 74.9);
            }
            dynamicProbability = Math.min(dynamicProbability, 99.9);
            today.probabilityPercent = Math.round(dynamicProbability * 100.0) / 100.0;
        } 
        catch (Exception e) {}
    }

	private double getTempRiver(List<String> activeTriggers) {
		if (activeTriggers == null) return 0.0;
		double riverVal = 0.0;
		Pattern pattern = Pattern.compile(
                ".*?(b-value|ETAS Swarm|Firehole River).*?\\(([-\\d\\.]+)\\)", 
                java.util.regex.Pattern.CASE_INSENSITIVE
            );
    	for (String trg : activeTriggers) {
            Matcher matcher = pattern.matcher(trg);
            if (matcher.find()) {
                String keyType = matcher.group(1).toLowerCase();
                double val = Double.parseDouble(matcher.group(2));
                if (keyType.contains("firehole river")) {
                    riverVal = val;
                    break;
                }
            }
        }
		return Math.abs(riverVal);
	}
}
