package com.argus.security.jwt;

/** Distinguishes user-access signing material from service workload signing material. */
public enum KeyPurpose {
    AUTH,
    WORKLOAD
}
