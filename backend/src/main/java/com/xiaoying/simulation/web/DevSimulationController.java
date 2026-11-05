package com.xiaoying.simulation.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.xiaoying.common.error.ApiException;
import com.xiaoying.common.error.ErrorCode;
import com.xiaoying.common.error.FieldErrorDto;
import com.xiaoying.common.error.GlobalExceptionHandler;
import com.xiaoying.simulation.SimulationConfig;
import com.xiaoying.simulation.SimulationSettings;

/**
 * {@code GET / PUT /api/dev/simulation}（api.yaml tag dev，F-08）。
 * 只在 {@code app.simulation.dev-api-enabled=true} 时注册：application-dev.yml / application-demo.yml 打开，
 * 其他 profile（含 prod）下该路由不存在 → 404 {@code NOT_FOUND}（architecture §7、R-06.2）。
 * 不需要登录（DQ-01，见 AuthInterceptor 白名单）。
 */
@RestController
@RequestMapping("/api/dev/simulation")
@ConditionalOnBooleanProperty("app.simulation.dev-api-enabled")
public class DevSimulationController {

    private final SimulationSettings settings;
    private final Validator validator;

    public DevSimulationController(SimulationSettings settings, Validator validator) {
        this.settings = settings;
        this.validator = validator;
    }

    @GetMapping
    public SimulationConfig get() {
        return settings.current();
    }

    /** 整体替换；只影响之后创建的任务（创建时快照，OQ-10）。 */
    @PutMapping
    public SimulationConfig put(@RequestBody SimulationConfigRequest request) {
        List<FieldErrorDto> errors = new ArrayList<>();
        validator.validate(request).stream()
                .sorted(Comparator.comparing((ConstraintViolation<?> v) -> v.getPropertyPath().toString()))
                .map(DevSimulationController::toFieldError)
                .forEach(errors::add);
        errors.addAll(request.structuralErrors());
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(),
                    Map.of("fieldErrors", errors));
        }
        return settings.update(request.toConfig());
    }

    private static FieldErrorDto toFieldError(ConstraintViolation<?> violation) {
        String constraint = violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
        return FieldErrorDto.of(violation.getPropertyPath().toString(),
                GlobalExceptionHandler.fieldCode(constraint), violation.getMessage());
    }
}
