package uz.habibi.bakery.web;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import uz.habibi.bakery.i18n.I18n;
import uz.habibi.bakery.i18n.Lang;
import uz.habibi.bakery.service.BusinessException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Mini App so'rovlariga X-Lang sarlavhasini qo'shadi (uz | cyr | ru). */
    private static Lang lang(HttpServletRequest request) {
        return Lang.fromCode(request.getHeader("X-Lang"));
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Map<String, String>> business(BusinessException e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, e.localized(lang(request)));
    }

    @ExceptionHandler(UnauthorizedException.class)
    ResponseEntity<Map<String, String>> unauthorized(UnauthorizedException e, HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, e.localized(lang(request)));
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<Map<String, String>> forbidden(ForbiddenException e, HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, e.localized(lang(request)));
    }

    private static final Map<String, String> FIELD_ERRORS = Map.of(
            "items", "err.cart_empty", "customerName", "err.name", "phone", "err.phone",
            "deliveryDate", "err.date", "deliveryTime", "err.time", "paymentMethod", "err.payment");

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e, HttpServletRequest request) {
        String key = e.getBindingResult().getFieldErrors().stream()
                .map(f -> FIELD_ERRORS.getOrDefault(f.getField().replaceAll("[\\[.].*", ""), "err.invalid"))
                .findFirst().orElse("err.invalid");
        return error(HttpStatus.BAD_REQUEST, I18n.t(lang(request), key));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> tooBig(Exception e, HttpServletRequest request) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, I18n.t(lang(request), "err.file_too_big"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, I18n.t(lang(request), "err.invalid"));
    }

    /** Mavjud bo'lmagan sahifa (favicon, botlar tekshiruvi va h.k.) — oddiy 404, xato emas. */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, String>> notFound(NoResourceFoundException e, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, I18n.t(lang(request), "err.not_found"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> other(Exception e, HttpServletRequest request) {
        log.error("Kutilmagan xato", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, I18n.t(lang(request), "err.server"));
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
