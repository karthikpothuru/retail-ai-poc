package com.retail.agent.context;

import com.google.adk.models.*;
import com.google.genai.types.*;
import io.reactivex.rxjava3.core.Flowable;
import java.util.*;

/** Final scripted answer is computed from actual received MCP response evidence, not seeded conclusions. */
final class EnterpriseScriptedModel extends BaseLlm {
    final List<LlmRequest> requests=new ArrayList<>();Runnable onCall=()->{};boolean fabricateCitation;
    EnterpriseScriptedModel(){super("enterprise-scripted-transport");}
    public Flowable<LlmResponse> generateContent(LlmRequest request,boolean stream){
        requests.add(request);onCall.run();Part part;
        switch(requests.size()){
            case 1 -> part=Part.fromFunctionCall("mcp_get_inventory",Map.of("productId","KB-201"));
            case 2 -> part=Part.fromFunctionCall("mcp_get_reorder_status",Map.of("productId","KB-201"));
            case 3 -> part=Part.fromFunctionCall("mcp_get_sales_summary",Map.of("productId","KB-201","fromDate","2026-11-01","toDate","2026-11-18"));
            default -> {
                String all=request.contents().toString();var matcher=java.util.regex.Pattern.compile("\\[DOC:[^\\]\\r\\n]{1,256}\\]").matcher(all);if(!matcher.find())throw new AssertionError("No document citation reached model");String citation=matcher.group();
                String answer="Dependency unavailable; current inventory cannot be established. ";
                for(var content:request.contents())for(var p:content.parts().orElse(List.of()))if(p.functionResponse().isPresent()){
                    var response=p.functionResponse().get();if(response.name().orElse("").equals("mcp_get_inventory")){
                        var body=response.response().orElse(Map.of());if(body.get("evidence") instanceof String json)try{var value=PostgresTransactions.JSON.readTree(json);int actual=value.path("actualInventory").asInt(),expected=value.path("expectedInventory").asInt();answer="Actual inventory "+actual+", expected "+expected+", variance "+(actual-expected)+". The cause remains unconfirmed pending recorded reconciliation evidence; recommendations are advisory. ";}catch(Exception invalid){throw new AssertionError("Tool evidence was not parseable");}
                    }
                }
                part=Part.fromText(answer+(fabricateCitation?"[DOC:"+UUID.randomUUID()+":v1:"+UUID.randomUUID()+"]":citation));
            }
        }
        return Flowable.just(LlmResponse.builder().content(Content.builder().role("model").parts(List.of(part)).build()).build());
    }
    public BaseLlmConnection connect(LlmRequest request){throw new UnsupportedOperationException("Scripted validation");}
}
