package com.retail.agent.context;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RetailInventoryMcpPoliciesTest {
    @Test void reviewedServerSchemasAndStrictModelSchemasRemainSeparate(){
        var tools=RetailInventoryMcpPolicies.tools();assertThat(tools).hasSize(3);
        assertThat(tools).extracting(SecureMcpGateway.ToolPolicy::remoteName).containsExactly("get_inventory","get_reorder_status","get_sales_summary");
        for(var tool:tools){assertThat(tool.schema().json()).containsEntry("additionalProperties",false);assertThat(tool.discoverySchema()).containsEntry("$schema","https://json-schema.org/draft/2020-12/schema");assertThat(tool.discoverySchema()).isNotEqualTo(tool.schema().json());}
    }
    @Test void nativeWarehouseSkusAndIdentityOverridesAreRejectedForProductService(){
        var tool=RetailInventoryMcpPolicies.tools().get(0);
        assertThat(tool.schema().validate(Map.of("productId","KB-201"))).containsEntry("productId","KB-201");
        assertThatThrownBy(()->tool.schema().validate(Map.of("productId","SKU-ELEC-1001"))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->tool.schema().validate(Map.of("productId","KB-201","tenantId","other"))).isInstanceOf(SecurityException.class);
    }
}
