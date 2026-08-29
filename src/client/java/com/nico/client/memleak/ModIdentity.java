package com.nico.client.memleak;

public record ModIdentity(String id, String name, String version) {
    public String displayName() {
        return name.equals(id) ? name : name + " (" + id + ")";
    }
}
