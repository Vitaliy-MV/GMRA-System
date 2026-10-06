package org.gmra.service;

import org.gmra.dao.SystemDao;
import org.gmra.integration.UsgsWaterClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class DataInitializationService {

	private final Tools tools;
    private final SystemDao systemDao;
    private final MoonDataCalculator moonDataCalculator;
    private final IscDataCalculator iscDataCalculator;
    private final UsgsWaterClient waterDataCalculator;
    private final MathCalculationService mathCalculationService;
    private final StatisticalBaselineService statisticalBaselineService;
    private final DataProfilerService dataProfile;
    private final MlPredictionService mlModel; 
    
    public DataInitializationService(SystemDao systemDao, Tools tools, 
                                     MoonDataCalculator moonDataCalculator, 
                                     IscDataCalculator iscDataCalculator,
                                     UsgsWaterClient waterDataCalculator,
                                     DataProfilerService dataProfilerService,
                                     MathCalculationService mathCalculationService,
                                     StatisticalBaselineService statisticalBaselineService,
                                     MlPredictionService mlModel) {
    	this.mlModel = mlModel;
    	this.tools = tools;
    	this.systemDao = systemDao;
        this.moonDataCalculator = moonDataCalculator;
        this.iscDataCalculator = iscDataCalculator;
        this.waterDataCalculator = waterDataCalculator;
        this.mathCalculationService = mathCalculationService;
        this.statisticalBaselineService = statisticalBaselineService;
        this.dataProfile = dataProfilerService;

    }
    
    // windowSize - is the sampling window 
    // minimum 3 days/points for correlation of the data.
    public static final int windowSize = 3;
    public static final double TARGET_MAGNITUDE = 6.9;

    @EventListener(ApplicationReadyEvent.class)
    public void initializeData() {
    	System.out.println("Start initialization...");
    	tools.writeLog("Start initialization source data");
        mathCalculationService.setSystemReady(false);
        systemDao.createSystemTables();
        syncAstronomyDates();
        syncMoonData();
        syncIscData();
        syncWaterData();
        
        // Recalculation of means and deviations (Z-score)
        statisticalBaselineService.calculateAndSaveBaselines();
        mathCalculationService.loadAstroDatesToCache();
        mathCalculationService.setScoringConfig(windowSize, TARGET_MAGNITUDE);
        dataProfile.setProfileCatastrophicEvents(windowSize, TARGET_MAGNITUDE);
        try {mlModel.trainAllModels();} 
        catch (Exception e) {	
        	tools.writeLog("Model training error: " + e.getMessage());
        	e.printStackTrace();
		}
        tools.writeLog("Data initialization complete");
        mathCalculationService.setSystemReady(true);
        System.out.println("Initialization complete.");
    }
    private void syncAstronomyDates() {
        String data = tools.readDataFile("astronomy_dates.txt");
        if (data == null || data.isBlank()) {
            tools.writeLog("File astronomy_dates.txt not found or empety.");
            return;
        }
        List<Object[]> batchArgs = new ArrayList<>();
        String[] lines = data.split("\\r?\\n");
        for (String line : lines) {
            if (line.contains("Year") || line.contains("UTC") || line.isBlank()) continue;
            String[] parts = line.trim().split("\\s+");
            try {
            	int year = Integer.parseInt(parts[0]);
            	String perihelion = parts[1];
            	String springEq = parts[2];
            	String aphelion = parts[3];
            	String autumnEq = parts[4];
            	batchArgs.add(new Object[]{year, perihelion, springEq, aphelion, autumnEq});
                } catch (Exception e) {e.printStackTrace();}
            }
        if (!batchArgs.isEmpty()) {
            systemDao.saveAstronomyDates(batchArgs);
        }
    }

    private void syncMoonData() {
        tools.writeLog("Start calculation lunar data...");
        MoonDataCalculator.MoonResult moonResult = moonDataCalculator.calculateAll();  
        if (moonResult != null && !moonResult.dailySpeeds.isEmpty()) {
            systemDao.saveMoonData(moonResult);
            tools.writeLog("Moon daily records saved/updated: " + moonResult.dailySpeeds.size());
            tools.writeLog("Orbital extremes: " + moonResult.listDates.size());
        } else {
            tools.writeLog("Data for the Moon not found.");
        }
    }

    private void syncIscData() {
        tools.writeLog("ISC Seismological data calculation...");
        IscDataCalculator.IscResult iscResult = iscDataCalculator.calculateAll();
        if (iscResult != null && !iscResult.listDates.isEmpty()) {
            systemDao.saveIscData(iscResult);
            tools.writeLog("Saved/updated daily seismological records: " + iscResult.listDates.size());
        } else {
            tools.writeLog("Data ISC not found.");
        }
    }
    
    private void syncWaterData() {
        tools.writeLog("USGS - water data calculation...");
        UsgsWaterClient.UsgsWaterResult usgsWaterTemp = waterDataCalculator.loadEndSetDataFromFiles();
        if (usgsWaterTemp != null && !usgsWaterTemp.listDates.isEmpty()) {
            systemDao.saveUsgsWaterData(usgsWaterTemp);
            tools.writeLog("Saved/updated daily water data: " + usgsWaterTemp.listDates.size());
        } else {
            tools.writeLog("Data USGS-water not found.");
        }
    }
}