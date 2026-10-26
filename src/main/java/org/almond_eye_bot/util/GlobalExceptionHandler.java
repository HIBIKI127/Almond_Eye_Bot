package org.almond_eye_bot.util;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleException(Exception e) {
        if (e instanceof ResponseStatusException status) {
            return ResponseEntity.status(status.getStatusCode()).body(Map.of("error",
                    status.getReason() == null ? "请求失败" : status.getReason()));
        }
        if (e instanceof MaxUploadSizeExceededException) {
            return ResponseEntity.status(413).body(Map.of("error", "上传文件超过请求大小限制（单文件最多 10MB）；资料 TXT 文件另限 512KB"));
        }
        if (e instanceof HttpMessageNotReadableException || e instanceof ServletRequestBindingException
                || e instanceof MethodArgumentTypeMismatchException) {
            return ResponseEntity.badRequest().body(Map.of("error", "请求参数不完整或格式不正确"));
        }
        if (e instanceof org.springframework.web.ErrorResponse response) {
            return ResponseEntity.status(response.getStatusCode()).body(Map.of("error",
                    response.getBody().getDetail() == null ? "请求失败" : response.getBody().getDetail()));
        }
        logger.error("处理请求时发生错误", e);
        return ResponseEntity.internalServerError().body(Map.of("error", "服务器处理失败，请检查日志后重试"));
    }
}
