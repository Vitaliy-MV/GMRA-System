package org.gmra.controller;

import org.gmra.dao.SystemDao;
import org.gmra.dto.PredictionResult;
import org.gmra.service.ForecastOrchestratorService;
import org.gmra.service.MathCalculationService;
import org.gmra.service.PredictionCalculator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
@CrossOrigin(origins = "*") // (React/Vue/Angular)
public class AnalyticsApiController {

	private final SystemDao systemDao;
    private final MathCalculationService mathCalculationService;
	private final ForecastOrchestratorService orchestratorService;

    public AnalyticsApiController(SystemDao systemDao, 
    		MathCalculationService mathCalculationService,
    		ForecastOrchestratorService orchestratorService,
    		PredictionCalculator prediction) {
        
    	this.systemDao = systemDao;
    	this.mathCalculationService = mathCalculationService;
    	this.orchestratorService = orchestratorService;
    }
    
    @GetMapping("/charts")
    public ResponseEntity<?> getChartData( @RequestParam(defaultValue = "7") int days,
            						@RequestParam(required = false) String endDate) {
    	
        if (!mathCalculationService.isSystemReady()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "System is calibrating or updating data. Please wait..."));
        }
        List<Map<String, Object>> allData = systemDao.getAllHistoricalDataJoined();
        int lastValidIndex = -1;

        // 1. Если с фронтенда пришла конкретная дата — ищем её индекс
        if (endDate != null && !endDate.isEmpty()) {
            LocalDate targetDate = LocalDate.parse(endDate);
            for (int i = 0; i < allData.size(); i++) {
                LocalDate rowDate = ((java.sql.Date) allData.get(i).get("stat_date")).toLocalDate();
                if (rowDate.equals(targetDate)) {
                    lastValidIndex = i;
                    break;
                }
            }
        }
        // 2. Если дата не передана (или не найдена), ищем последний день с реальной сейсмикой
        if (lastValidIndex == -1) {
            lastValidIndex = allData.size() - 1;
            while (lastValidIndex >= 0 && allData.get(lastValidIndex).get("sum_magnitude") == null) {
                lastValidIndex--;
            }
        }
        if (lastValidIndex < 0) {
            return ResponseEntity.ok(new ArrayList<>());
        }
        // 3. Отсчитываем дни назад от найденного индекса
        int startIndex = Math.max(0, lastValidIndex - days + 1);
        List<Map<String, Object>> requestedData = allData.subList(startIndex, lastValidIndex + 1);
        List<Map<String, Object>> formattedData = new ArrayList<>();
        for (Map<String, Object> row : requestedData) {
            Map<String, Object> dto = new HashMap<>(row);
            dto.put("stat_date", row.get("stat_date").toString());
            formattedData.add(dto);
        }
        return ResponseEntity.ok(formattedData);
    }
    
    @GetMapping("/forecast")
    public ResponseEntity<?> getForecasts(@RequestParam(required = false) String date) {
    	if (!mathCalculationService.isSystemReady()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "System is updating..."));
        }
    	LocalDate baseDate;
        try {
            if (date != null && !date.isBlank() && !"YYYY-MM-DD".equalsIgnoreCase(date)) {
                baseDate = LocalDate.parse(date);
            }
            else {
                baseDate = LocalDate.now();
            }
        }
        catch (DateTimeParseException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Invalid date format. Please use YYYY-MM-DD format."));
        }
        Map<String, PredictionResult> forecasts = orchestratorService.generateFullForecast(baseDate, null);
        return ResponseEntity.ok(forecasts);
    }
}