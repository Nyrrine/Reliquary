package com.nyrrine.reliquary.data;

/** Enumerable role grants. Separate from PlayerRecord precisely so it CAN be enumerated. */
public interface RoleIndex {

    boolean has(java.util.UUID id, String role);

    void grant(java.util.UUID id, String role);

    void revoke(java.util.UUID id, String role);

    /** Everyone holding this role. Enumeration lives here — this is what backs /prescript weaver list. */
    java.util.Set<java.util.UUID> all(String role);
}
