package com.bencodez.gravestonesplus.nbt;

public class NBTRule {
    private String name;
    private String path;
    private String type; // STRING, INTEGER, BOOLEAN, DOUBLE, BYTE, SHORT, LONG, FLOAT, EXISTS
    private Object value;

    public NBTRule(String name, String path, String type, Object value) {
        this.name = name;
        this.path = path;
        this.type = type;
        this.value = value;
    }

    // Getters
    public String getName() { return name; }
    public String getPath() { return path; }
    public String getType() { return type; }
    public Object getValue() { return value; }

    @Override
    public String toString() {
        return "NbtRule{" +
                "name='" + name + '\'' +
                ", path='" + path + '\'' +
                ", type='" + type + '\'' +
                ", value=" + value +
                '}';
    }
}
