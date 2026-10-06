package org.gmra.exception;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.gmra.config.BootApplication;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Controller
public class ErrController implements ErrorController {
    
    private final static String logFile = BootApplication.DIRSysFiles + "Log.txt";
    private final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("dd-MM-yyyy ' | ' HH:mm:ss");

    @RequestMapping("/error")
    public String handleError(HttpServletRequest req, Model model) {
        Object status = req.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int statusCode = status != null ? Integer.parseInt(status.toString()) : 520;
        
        String message = "520 Unknown Error";
        
        if (statusCode == HttpStatus.NOT_FOUND.value()) {
            message = "404 Page not found";
        } else if (statusCode == HttpStatus.FORBIDDEN.value()) {
            message = "403 Page Forbidden";
        } else if (statusCode == HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            message = "500 INTERNAL SERVER ERROR";
            logError(req, statusCode);
        } else if (statusCode == HttpStatus.BAD_REQUEST.value()) {
            message = "400 BAD REQUEST";
        } else if (statusCode == HttpStatus.METHOD_NOT_ALLOWED.value()) {
            message = "405 Method Not Allowed";
        } else if (statusCode == HttpStatus.TOO_MANY_REQUESTS.value()) {
            message = "429 Too many Requests";
        } else {
            logError(req, statusCode);
        }

        model.addAttribute("message", message);
        return "error-page";
    }

    private void logError(HttpServletRequest req, int statusCode) {
        String ip = getClientIp(req);
        Object exception = req.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        String exMsg = exception != null ? exception.toString() : "Unknown Exception";
        writeLog(ip, exMsg, statusCode);
    }

    private synchronized void writeLog(String ip, String data, int statusCode) {
        String logLine = ip + " | Error:" + statusCode + " | " + LocalDateTime.now().format(DATETIME) + " | " + data;
        Path filePath = Paths.get(logFile);
        try {
            if (!Files.exists(filePath)) {
                Files.createDirectories(filePath.getParent()); // Безопасное создание папок, если их нет
                Files.createFile(filePath);
            }
            Files.writeString(filePath, logLine + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String ipAddress = request.getHeader("X-Forwarded-For");
        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress))
            ipAddress = request.getHeader("Proxy-Client-IP");
        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress))
            ipAddress = request.getHeader("WL-Proxy-Client-IP");
        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress))
            ipAddress = request.getRemoteAddr();
            
        if (ipAddress != null && ipAddress.contains(",")) {
            return ipAddress.split(",")[0].trim();
        }
        return ipAddress;
    }
}