package com.xiaoying.catalog.domain;

/** {@code categories} 表行（一级分类，只读字典）。 */
public class Category {

    private String id;
    private String name;
    private int sortOrder;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
