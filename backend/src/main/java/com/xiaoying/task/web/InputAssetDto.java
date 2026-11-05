package com.xiaoying.task.web;

/** api.yaml InputAsset。{@code url} = {@code /files/} + storage_key；storage_key 本身不对外。 */
public record InputAssetDto(int slotIndex, String url) {
}
