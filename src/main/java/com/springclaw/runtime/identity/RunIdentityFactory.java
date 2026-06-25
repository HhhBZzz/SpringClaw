package com.springclaw.runtime.identity;

public interface RunIdentityFactory {

    String create();

    String accept(String suppliedRunId);
}
