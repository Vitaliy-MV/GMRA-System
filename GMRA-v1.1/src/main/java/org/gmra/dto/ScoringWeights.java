package org.gmra.dto;

public class ScoringWeights {
    // ASTRONOMY
    public double weightAphelion = 1.0;         // Earth near Aphelion/Perihelion
    public double weightEquinox = 1.0;          // Earth near the Equinox
    public double weightMoonAreaPhase = 1.0;    // Phase change of the lunar orbital area
    public double weightMomentumExt = 1.0;      // Momentum Derivatives (Acceleration) <=3 days to extremum by avg_speed
    public double weightSpeedMoonExt = 1.0;     // Speed ​​Moon <=3 days to its extremum by avg_speed
    
    // SEISMIC CORRELATIONS
    public double weightPearsonMD = 1.0;        // Magnitude -vs- Depth
    public double weightSpearmanMD = 1.0;       // Magnitude -vs- Depth
    public double weightDepthAnomaly = 1.0;     // Global Earthquake Depth Dispersion Anomaly (sigma)
    
    // SEISMIC ADVANCED
    public double weightBValueAnomaly = 1.0;       // Anomaly b-value (Gutenberg-Richter Law)
    public double weightEnergyAcceleration = 1.0;  // AMR (Accelerated Moment Release / Benioff's Energy Release)
    public double weightSeismicQuiescence = 1.0;   // Seismic silence (Benioff's Energy Release)
    public double weightEventSwarm = 1.0;          // Seismic swarm ETAS (Epidemic-Type Aftershock Sequence)
    
    /**
     * Calculates the theoretical absolute maximum score.
     * Automatically accounts for mutually exclusive triggers.
     */
    public double getTotalMaxScore() { 
        double maxEnergy = Math.max(weightEnergyAcceleration, weightSeismicQuiescence);
        double maxAstro = Math.max(weightAphelion, weightEquinox);
        
        return maxAstro + weightPearsonMD + weightSpearmanMD 
                + weightMoonAreaPhase + weightMomentumExt + weightSpeedMoonExt 
                + weightDepthAnomaly + weightBValueAnomaly + maxEnergy + weightEventSwarm;
    }
}