package com.retail.agent.context;

import java.util.*;
import static com.retail.agent.context.MemoryTypes.*;

/** Reviewed server-source snapshots. Remote descriptions never become agent instructions. */
public final class RetailInventoryMcpPolicies {
    private RetailInventoryMcpPolicies(){}
    public static List<SecureMcpGateway.ToolPolicy> tools(){
        var product=new McpInputSchema.Field(true,16,"[A-Z]{2}-[0-9]{3}",Set.of("WM-101","KB-201","MN-301"));
        var date=new McpInputSchema.Field(true,10,"[0-9]{4}-[0-9]{2}-[0-9]{2}",Set.of());
        var single=new McpInputSchema(Map.of("productId",product));
        var sales=new McpInputSchema(Map.of("productId",product,"fromDate",date,"toDate",date));
        return List.of(
            new SecureMcpGateway.ToolPolicy("mcp_get_inventory","get_inventory","Read product inventory from the separate demonstration product service. Product IDs differ from native warehouse SKUs.",single,Sensitivity.INTERNAL,true,
                remote(Map.of("productId",property("Product ID, for example WM-101, KB-201, or MN-301")),List.of("productId"))),
            new SecureMcpGateway.ToolPolicy("mcp_get_reorder_status","get_reorder_status","Read advisory product reorder status. No order is created.",single,Sensitivity.INTERNAL,true,
                remote(Map.of("productId",property("Product ID, for example WM-101")),List.of("productId"))),
            new SecureMcpGateway.ToolPolicy("mcp_get_sales_summary","get_sales_summary","Read sample product sales quantity. Quantity is not date-filtered; dates only describe the requested interval.",sales,Sensitivity.INTERNAL,true,
                remote(Map.of("productId",property("Product ID, for example WM-101"),"fromDate",property("Start date in yyyy-MM-dd format"),"toDate",property("End date in yyyy-MM-dd format")),List.of("productId","fromDate","toDate")))
        );
    }
    private static Map<String,Object> property(String description){return Map.of("type","string","description",description);}
    private static Map<String,Object> remote(Map<String,Object> fields,List<String> required){return Map.of("$schema","https://json-schema.org/draft/2020-12/schema","type","object","properties",fields,"required",required);}
}
