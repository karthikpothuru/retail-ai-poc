package com.retail.customer;

import java.util.UUID;

@FunctionalInterface
interface CustomerIdentityProvider {
    UUID requireCustomerId();
}
