package org.gmra.dao;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.gmra.dto.ScoringWeights;
import org.gmra.integration.UsgsWaterClient;
import org.gmra.service.IscDataCalculator;
import org.gmra.service.MoonDataCalculator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Repository;

@Repository
public class SystemDao {

	private final static DataSource dataSource = dataSource();
    private final JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    
    private static DataSource dataSource() {
	    DriverManagerDataSource dataSource = new DriverManagerDataSource();
	    dataSource.setUsername("Admin");
	    dataSource.setPassword("password");
	    dataSource.setUrl("jdbc:mysql://localhost/");
	    try { 
	    	dataSource.getConnection().createStatement()
	    	.execute("CREATE DATABASE IF NOT EXISTS gmra_db "
	    			+ "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
		} catch (SQLException e) {	e.printStackTrace();	}
	    dataSource.setUrl("jdbc:mysql://localhost/gmra_db");
	    return dataSource;
	}
    public void createSystemTables() {
        jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS moon_stats ("
                + "stat_date DATE PRIMARY KEY,"
                + "avg_speed DOUBLE,"
                + "speed_derivative DOUBLE,"
                + "avg_distance DOUBLE,"
                + "orbit_area DOUBLE,"
                + "phase_speed DOUBLE)"
        );
        
        jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS earthquake_daily_stats ("
                + "stat_date DATE PRIMARY KEY,"
                + "avg_depth DOUBLE,"
                + "sum_magnitude DOUBLE,"
                + "max_magnitude DOUBLE,"
                + "event_count INT,"
                + "b_value DOUBLE,"
                + "benioff_strain DOUBLE)"
        );
        
        jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS scoring_config ("
                + "id INT PRIMARY KEY,"
                + "weight_aphelion DOUBLE,"
                + "weight_equinox DOUBLE,"
                + "weight_pearson_md DOUBLE,"
                + "weight_spearman_md DOUBLE,"
                + "weight_moon_area DOUBLE,"
                + "weight_momentum_ext DOUBLE,"
                + "weight_speed_ext DOUBLE,"
                + "weight_depth_anomaly DOUBLE,"
                + "weight_bvalue_anomaly DOUBLE,"
                + "weight_energy_accel DOUBLE,"
                + "weight_event_swarm DOUBLE,"
                + "weight_seismic_quiescence DOUBLE)"
        );
        
        jdbcTemplate.execute(
            "INSERT IGNORE INTO scoring_config " + 
            "VALUES (1, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)"
        );
        
        jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS statistical_baselines ("
                + "parameter_name VARCHAR(50) PRIMARY KEY,"
                + "mean DOUBLE,"
                + "sigma DOUBLE)"   
        );
        
        jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS astronomy_dates ("
                + "stat_year INT PRIMARY KEY,"
                + "perihelion DATE,"
                + "spring_eq DATE,"
                + "aphelion DATE,"
                + "autumn_eq DATE)"
        );
        // Table of profiles M >= 6.9
        jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS seismic_events_profile ("
                + "event_date DATE PRIMARY KEY, "
                + "magnitude DOUBLE, "
                // -- Pearson
                + "pearson_day_0 DOUBLE, "
                + "pearson_day_1 DOUBLE, "
                + "pearson_day_2 DOUBLE, "
                + "pearson_day_7 DOUBLE, "
                // -- Spearman
                + "spearman_day_0 DOUBLE, "
                + "spearman_day_1 DOUBLE, "
                + "spearman_day_2 DOUBLE, "
                + "spearman_day_7 DOUBLE, "
                // -- Benioff
                + "benioff_vel_day_0 DOUBLE, "
                + "benioff_vel_day_1 DOUBLE, "
                + "benioff_vel_day_2 DOUBLE, "
                + "benioff_vel_day_7 DOUBLE, "
                // -- Depth 
                + "depth_delta_day_0 DOUBLE, "
                + "depth_delta_day_1 DOUBLE, "
                + "depth_delta_day_2 DOUBLE, "
                + "depth_delta_day_7 DOUBLE, "
                // -- b-value
                + "b_value_day_0 DOUBLE, "
                + "b_value_day_1 DOUBLE, "
                + "b_value_day_2 DOUBLE, "
                + "b_value_day_7 DOUBLE, "
                // -- ETAS
                + "etas_delta_day_0 DOUBLE, "
                + "etas_delta_day_1 DOUBLE, "
                + "etas_delta_day_2 DOUBLE, "
                + "etas_delta_day_7 DOUBLE)"
        );
        
        jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS catastrophic_baselines ("
                + "trigger_name VARCHAR(50) PRIMARY KEY, "
                
                + "mean_day_0 DOUBLE, "
                + "median_day_0 DOUBLE, "
                
                + "mean_day_1 DOUBLE, "
                + "median_day_1 DOUBLE, "
                
                + "mean_day_2 DOUBLE, "
                + "median_day_2 DOUBLE, "
                
                + "mean_day_7 DOUBLE, "
                + "median_day_7 DOUBLE)"
        );
        
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS usgs_water_stats ("
                    + "stat_date DATE PRIMARY KEY,"
                    + "mean_temp DOUBLE,"
                    + "median_temp DOUBLE)"
        );
    }
    
    public void saveAstronomyDates(List<Object[]> batchArgs) {
        String sql = "INSERT INTO astronomy_dates (stat_year, perihelion, spring_eq, aphelion, autumn_eq) " +
                     "VALUES (?, ?, ?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE perihelion = VALUES(perihelion), " +
                     "spring_eq = VALUES(spring_eq), aphelion = VALUES(aphelion), autumn_eq = VALUES(autumn_eq)";
        jdbcTemplate.batchUpdate(sql, batchArgs);
    }

    public void saveMoonData(MoonDataCalculator.MoonResult result) {
        String sqlDaily = "INSERT INTO moon_stats (stat_date, avg_speed, avg_distance, speed_derivative) VALUES (?, ?, ?, ?) " +
                          "ON DUPLICATE KEY UPDATE avg_speed = VALUES(avg_speed), avg_distance = VALUES(avg_distance), " +
                          "speed_derivative = VALUES(speed_derivative)";
        List<Object[]> batchDaily = new ArrayList<>();
        for (Map.Entry<String, Double> entry : result.dailySpeeds.entrySet()) {
            String date = entry.getKey();
            Double speed = entry.getValue();
            Double distance = result.dailyDistance.get(date);
            Double derivative = result.dailySpeedDerivative.get(date);
            batchDaily.add(new Object[]{date, speed, distance, derivative});
        }
        jdbcTemplate.batchUpdate(sqlDaily, batchDaily);
 
        String sqlPhase = "UPDATE moon_stats SET orbit_area = ?, phase_speed = ? WHERE stat_date = ?";
        List<Object[]> batchPhase = new ArrayList<>();
        for (Map.Entry<String, Double> entry : result.areaByDate.entrySet()) {
            String date = entry.getKey();
            Double area = entry.getValue();
            Double speed = result.phaseSpeeds.get(date);
            batchPhase.add(new Object[]{area, speed, date});
        }
        jdbcTemplate.batchUpdate(sqlPhase, batchPhase);
    }
    
    public void saveIscData(IscDataCalculator.IscResult result) {
        String sql = "INSERT INTO earthquake_daily_stats (stat_date, avg_depth, sum_magnitude, max_magnitude, event_count, b_value, benioff_strain) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE avg_depth = VALUES(avg_depth), " +
                     "sum_magnitude = VALUES(sum_magnitude), " +
                     "max_magnitude = VALUES(max_magnitude), " +
                     "event_count = VALUES(event_count), " +
                     "b_value = VALUES(b_value), "+
                     "benioff_strain = VALUES(benioff_strain)";
        
        List<Object[]> batchArgs = new ArrayList<>();
        for (String date : result.listDates) {
            Double depth = result.depthByDate.get(date);
            Double magnitude = result.magnitudeByDate.get(date);
            Double maxmagnitude = result.maxMagnitudeByDate.get(date);
            Integer count = result.countByDate.get(date);
            Double bValue = result.bValueByDate.get(date);
            Double benioffEnergy = result.benioffByDate.get(date);
            
            batchArgs.add(new Object[]{date, depth, magnitude, maxmagnitude, count, bValue, benioffEnergy});
        }
        
        jdbcTemplate.batchUpdate(sql, batchArgs);
    }
    
    public void saveBaseline(String parameterName, double mean, double sigma) {
        String sql = "INSERT INTO statistical_baselines (parameter_name, mean, sigma) " +
                     "VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE mean = VALUES(mean), sigma = VALUES(sigma)";
        jdbcTemplate.update(sql, parameterName, mean, sigma);
    }
    
    public ScoringWeights getScoringWeights() {
        String sql = "SELECT * FROM scoring_config WHERE id = 1";
        return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
            ScoringWeights weights = new ScoringWeights();
            weights.weightAphelion = rs.getDouble("weight_aphelion");
            weights.weightEquinox = rs.getDouble("weight_equinox");
            weights.weightPearsonMD = rs.getDouble("weight_pearson_md");
            weights.weightSpearmanMD = rs.getDouble("weight_spearman_md");
            weights.weightMoonAreaPhase = rs.getDouble("weight_moon_area");
            weights.weightMomentumExt = rs.getDouble("weight_momentum_ext");
            weights.weightSpeedMoonExt = rs.getDouble("weight_speed_ext");
            weights.weightDepthAnomaly = rs.getDouble("weight_depth_anomaly");
            weights.weightBValueAnomaly = rs.getDouble("weight_bvalue_anomaly");
            weights.weightEnergyAcceleration = rs.getDouble("weight_energy_accel");
            weights.weightEventSwarm = rs.getDouble("weight_event_swarm");
            weights.weightSeismicQuiescence = rs.getDouble("weight_seismic_quiescence");
            return weights;
        });
    }

    // --- StatisticalBaselineService Methods ---
    public List<Double> getAllMoonSpeeds() {
        String sql = "SELECT avg_speed FROM moon_stats WHERE avg_speed IS NOT NULL";
        return jdbcTemplate.queryForList(sql, Double.class);
    }
    
    public List<Double> getAllMoonSpeedDerivative() {
        String sql = "SELECT speed_derivative FROM moon_stats WHERE speed_derivative IS NOT NULL";
        return jdbcTemplate.queryForList(sql, Double.class);
    }
    
    public List<Double> getAllDepths() {
        String sql = "SELECT avg_depth FROM earthquake_daily_stats WHERE avg_depth IS NOT NULL";
        return jdbcTemplate.queryForList(sql, Double.class);
    }
    
    public List<Double> getAllMagnitudes() {
        String sql = "SELECT sum_magnitude FROM earthquake_daily_stats WHERE sum_magnitude IS NOT NULL";
        return jdbcTemplate.queryForList(sql, Double.class);
    }
    
    public List<Double> getAllEventCounts() {
        String sql = "SELECT CAST(event_count AS DOUBLE) FROM earthquake_daily_stats WHERE event_count IS NOT NULL";
        return jdbcTemplate.queryForList(sql, Double.class);
    }
    
    public List<Double> getAllBenioffStrains() {
        String sql = "SELECT benioff_strain FROM earthquake_daily_stats WHERE benioff_strain IS NOT NULL";
        return jdbcTemplate.queryForList(sql, Double.class);
    }
    
    public List<Map<String, Object>> getAllAstronomyDates() {
        String sql = "SELECT stat_year, perihelion, spring_eq, aphelion, autumn_eq FROM astronomy_dates";
        return jdbcTemplate.queryForList(sql);
    }
    
    public List<Double> getAllWaterTemp() {
        String sql = "SELECT CAST(mean_temp AS DOUBLE) FROM usgs_water_stats WHERE mean_temp IS NOT NULL";
        return jdbcTemplate.queryForList(sql, Double.class);
    }
    
    public List<Map<String, Object>> getAllHistoricalDataJoined() {
        String sql = "SELECT m.stat_date, e.avg_depth, e.sum_magnitude, e.max_magnitude, e.event_count, e.b_value, e.benioff_strain, "
                + "m.avg_speed, m.speed_derivative, m.orbit_area, m.avg_distance, "
                + "w.mean_temp, w.median_temp "
                + "FROM moon_stats m "
                + "LEFT JOIN earthquake_daily_stats e ON m.stat_date = e.stat_date "
                + "LEFT JOIN usgs_water_stats w ON m.stat_date = w.stat_date "
                + "ORDER BY m.stat_date ASC";
        return jdbcTemplate.queryForList(sql);
    }
    
    public void updateScoringWeights(ScoringWeights w) {
        String sql =" UPDATE scoring_config SET "
                + "weight_aphelion = ?, weight_equinox = ?, weight_pearson_md = ?,"
                + "weight_spearman_md = ?, weight_moon_area = ?, weight_momentum_ext = ?,"
                + "weight_speed_ext = ?, weight_depth_anomaly = ?, weight_bvalue_anomaly = ?,"
                + "weight_energy_accel = ?, weight_event_swarm = ?, weight_seismic_quiescence = ? "
                + "WHERE id = 1";
        
        jdbcTemplate.update(sql, 
            w.weightAphelion, w.weightEquinox, w.weightPearsonMD, w.weightSpearmanMD, 
            w.weightMoonAreaPhase, w.weightMomentumExt, w.weightSpeedMoonExt, w.weightDepthAnomaly, 
            w.weightBValueAnomaly, w.weightEnergyAcceleration, w.weightEventSwarm, w.weightSeismicQuiescence
        );
    }
   
    public void updateEarthquakeStatistics(LocalDate statDate, double avgDepth, double sumMagnitude, double maxMagnitude, int eventCount, double bValue, double benioffStrain) {
        String sql = "INSERT INTO earthquake_daily_stats (stat_date, avg_depth, sum_magnitude, max_magnitude, event_count, b_value, benioff_strain) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE "
                + "avg_depth = VALUES(avg_depth), "
                + "sum_magnitude = VALUES(sum_magnitude), "
                + "max_magnitude = VALUES(max_magnitude), "
                + "event_count = VALUES(event_count), "
                + "b_value = VALUES(b_value), "
                + "benioff_strain = VALUES(benioff_strain)";
        jdbcTemplate.update(sql, java.sql.Date.valueOf(statDate), avgDepth, sumMagnitude, maxMagnitude, eventCount, bValue, benioffStrain);
    }

    public void saveEventProfile(java.sql.Date eventDate, double magnitude, Map<String, Double> m) {
        jdbcTemplate.update(
            "INSERT INTO seismic_events_profile (event_date, magnitude, " +
            "pearson_day_0, pearson_day_1, pearson_day_2, pearson_day_7, " +
            "spearman_day_0, spearman_day_1, spearman_day_2, spearman_day_7, " +
            "benioff_vel_day_0, benioff_vel_day_1, benioff_vel_day_2, benioff_vel_day_7, " +
            "depth_delta_day_0, depth_delta_day_1, depth_delta_day_2, depth_delta_day_7, " +
            "b_value_day_0, b_value_day_1, b_value_day_2, b_value_day_7, " +
            "etas_delta_day_0, etas_delta_day_1, etas_delta_day_2, etas_delta_day_7) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            eventDate, magnitude,
            m.get("pearson_day_0"), m.get("pearson_day_1"), m.get("pearson_day_2"), m.get("pearson_day_7"),
            m.get("spearman_day_0"), m.get("spearman_day_1"), m.get("spearman_day_2"), m.get("spearman_day_7"),
            m.get("benioff_vel_day_0"), m.get("benioff_vel_day_1"), m.get("benioff_vel_day_2"), m.get("benioff_vel_day_7"),
            m.get("depth_delta_day_0"), m.get("depth_delta_day_1"), m.get("depth_delta_day_2"), m.get("depth_delta_day_7"),
            m.get("b_value_day_0"), m.get("b_value_day_1"), m.get("b_value_day_2"), m.get("b_value_day_7"),
            m.get("etas_delta_day_0"), m.get("etas_delta_day_1"), m.get("etas_delta_day_2"), m.get("etas_delta_day_7")
        );
    }
    
    public void saveCatastrophicBaselines(List<Object[]> batchArgs) {
        String sql = "INSERT INTO catastrophic_baselines (trigger_name, " +
                     "mean_day_0, median_day_0, mean_day_1, median_day_1, " +
                     "mean_day_2, median_day_2, mean_day_7, median_day_7) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";   
        
       jdbcTemplate.batchUpdate(sql, batchArgs);
    }
    
    public void cleanTableEventProfile() {
    	jdbcTemplate.execute("TRUNCATE TABLE seismic_events_profile");
    	jdbcTemplate.execute("TRUNCATE TABLE catastrophic_baselines");;
    }
    
    public void updateUsgsWaterTemperature(LocalDate statDate, double meanTemp, double medianTemp) {
        String sql = "INSERT INTO usgs_water_stats (stat_date, mean_temp, median_temp) "
                + "VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE "
                + "mean_temp = VALUES(mean_temp), "
                + "median_temp = VALUES(median_temp)";
        jdbcTemplate.update(sql, java.sql.Date.valueOf(statDate), meanTemp, medianTemp);
    }

    public List<Double> getAllUsgsWaterData() {
        String sql = "SELECT mean_temp FROM usgs_water_stats WHERE mean_temp IS NOT NULL";
        return jdbcTemplate.queryForList(sql, Double.class);
    }

    public void saveUsgsWaterData(UsgsWaterClient.UsgsWaterResult result) {
        String sql = "INSERT INTO usgs_water_stats (stat_date, mean_temp, median_temp) " +
                     "VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE mean_temp = VALUES(mean_temp), " +
                     "median_temp = VALUES(median_temp)";
        
        List<Object[]> batchArgs = new ArrayList<>();
        for (int i = 0; i < result.listDates.size(); i++) {
            LocalDate date = result.listDates.get(i);
            Double mean = result.mean.get(i);
            Double median = result.median.get(i);
            
            batchArgs.add(new Object[]{java.sql.Date.valueOf(date), mean, median});
        }
        
        jdbcTemplate.batchUpdate(sql, batchArgs);
    }
 }