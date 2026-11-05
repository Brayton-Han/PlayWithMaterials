package com.xiaoying.storage;

/** 文件读写失败（磁盘错误、权限等）。调用方按未预期错误处理（500 INTERNAL_ERROR），并 rollback 本批文件。 */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
