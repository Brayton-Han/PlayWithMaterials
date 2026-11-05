package com.xiaoying.common.error;

import org.springframework.http.HttpStatus;

/**
 * 稳定的机器可读错误码，与 docs/api.yaml 中 {@code components.schemas.ErrorCode} 一一对应。
 * 新增错误码时必须先改契约。
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "请求参数不合法"),
    IMAGE_COUNT_MISMATCH(HttpStatus.BAD_REQUEST, "上传图片数量与模板要求不一致"),
    ASSET_VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "部分图片不符合要求"),
    CURRENT_PASSWORD_INCORRECT(HttpStatus.BAD_REQUEST, "当前密码不正确"),
    CONFIRM_TEXT_MISMATCH(HttpStatus.BAD_REQUEST, "请输入 DELETE 以确认删除"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "请先登录"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
    TEMPLATE_NOT_FOUND(HttpStatus.NOT_FOUND, "模板不存在"),
    TASK_NOT_FOUND(HttpStatus.NOT_FOUND, "任务不存在"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持"),
    USERNAME_TAKEN(HttpStatus.CONFLICT, "用户名已被使用"),
    EMAIL_TAKEN(HttpStatus.CONFLICT, "邮箱已被使用"),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "上传内容过大"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请求格式不支持"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务器开小差了，请稍后重试"),
    QUEUE_FULL(HttpStatus.SERVICE_UNAVAILABLE, "当前制作任务较多，请稍后再试");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
