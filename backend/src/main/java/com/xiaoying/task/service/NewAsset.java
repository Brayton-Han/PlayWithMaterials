package com.xiaoying.task.service;

import com.xiaoying.storage.StoredFile;

/** 一个已暂存、待写入 {@code task_assets} 的素材。{@code originalName} 只用于排查，不用于拼路径。 */
public record NewAsset(int slotIndex, StoredFile file, String originalName) {
}
