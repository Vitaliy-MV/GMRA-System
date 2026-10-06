package org.gmra.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class PredictionResult {
    
	public LocalDate targetDate;
    public int windowSize;
    public double probabilityPercent;
    public List<String> activeTriggers = new ArrayList<>();
   
}
