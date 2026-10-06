package org.gmra.service;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.gmra.dao.SystemDao;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StatisticalBaselineService {

    private final Tools tools;
    private final SystemDao systemDao;
    private final MathCalculationService mathCalculationService;

    public StatisticalBaselineService(Tools tools, SystemDao systemDao,
    					MathCalculationService mathCalculationService) {
        this.tools = tools;
        this.systemDao = systemDao;
        this.mathCalculationService = mathCalculationService;
    }

    public void calculateAndSaveBaselines() {
        tools.writeLog("Start calculation statistical normals...");
        List<Double> moonSpeeds = systemDao.getAllMoonSpeeds();
        calculateForParameter("moon_speed", moonSpeeds);
        
        List<Double> moonSpeedsDerivative = systemDao.getAllMoonSpeedDerivative();
        calculateForParameter("moon_speed_derivative", moonSpeedsDerivative);

        List<Double> historicalDepths = systemDao.getAllDepths();
        calculateForParameter("global_depth", historicalDepths);
        
        List<Double> historicalMagnitudes = systemDao.getAllMagnitudes();
        calculateForParameter("sum_magnitude", historicalMagnitudes);

        List<Double> historicalEventCountsETAS = systemDao.getAllEventCounts();
        calculateForParameter("event_count", historicalEventCountsETAS);
        
        List<Double> historicalBenioff = systemDao.getAllBenioffStrains();
        calculateForParameter("benioff_strain", historicalBenioff);
        
        List<Double> historicalTempWater = systemDao.getAllWaterTemp();
        calculateForParameter("water_temp", historicalTempWater);
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