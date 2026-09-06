package com.apparat.policy;

import com.apparat.model.Resource;

public final class PolicyFactory {
    private PolicyFactory() { }

    public static BookingPolicy forResource(Resource resource) {
        return resource.isRequiresApproval() ? new SupervisedPolicy() : new StandardPolicy();
    }
}
