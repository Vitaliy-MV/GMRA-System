package org.gmra.service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.gmra.config.BootApplication;

@Component
public class Tools {
	private final static String logFile = BootApplication.DIRSysFiles + "Log.txt";
	private final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("dd-MM-yyyy ' | ' HH:mm:ss");
	private static final Pattern EMAIL_PATTERN = Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");
	
    @Value("${application.host.url}")
    private String applicationHostUrl;
	 
    public String readDataFile(String nameFile) {
		String data=null;
		try {
			File file = new File (BootApplication.DIRSysFiles+nameFile);
			if(file.exists()) {
				byte [] content = Files.readAllBytes(file.toPath());
				data = new String (content, StandardCharsets.UTF_8);
			}
		} catch (Exception e) {e.printStackTrace();}
	return data;
	}	
    
    public void writeDataFile(String data, Path filePath) {
		try {
			if (!Files.exists(filePath)) {
				File file = new File(filePath.toString());
				file.createNewFile();
			}
			Files.writeString(filePath, data+
						System.lineSeparator(),
						StandardCharsets.UTF_8, 
						StandardOpenOption.APPEND);
		} catch (IOException e) 
		{ e.printStackTrace(); }
	}
    
    public synchronized void writeLog(String data) {
        String logLine = LocalDateTime.now().format(DATETIME) + " | " + data;
        Path filePath = Paths.get(logFile);
        try {
            if (!Files.exists(filePath)) {
                Files.createDirectories(filePath.getParent());
                Files.createFile(filePath);
            }
            Files.writeString(filePath, logLine + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
	 /**
     * @param email
     * @return true, if email is correct, otherwise false. */
    public boolean isValidEmailFormat(String email) {
        if (email == null || email.isEmpty()) {
            return false;
        }
        Matcher matcher = EMAIL_PATTERN.matcher(email);
        return matcher.matches();
    }
    
    public String getAppHostUrl() {
    	return applicationHostUrl;
    }
    
}