package com.retail.agent.context;

import com.google.adk.models.*;
import com.google.genai.types.*;
import io.reactivex.rxjava3.core.Flowable;
import java.util.*;

final class RuntimeScriptedModel extends BaseLlm {
    final List<LlmRequest> requests=new ArrayList<>();
    private final Queue<Part> responses;
    Runnable onCall=()->{};
    RuntimeScriptedModel(Part... responses){super("offline-runtime-model");this.responses=new ArrayDeque<>(List.of(responses));}
    public Flowable<LlmResponse> generateContent(LlmRequest request,boolean stream){
        requests.add(request);onCall.run();if(responses.isEmpty())return Flowable.error(new AssertionError("Unexpected model invocation"));
        return Flowable.just(LlmResponse.builder().content(Content.builder().role("model").parts(List.of(responses.remove())).build()).build());
    }
    public BaseLlmConnection connect(LlmRequest request){throw new UnsupportedOperationException("Offline");}
    static RuntimeScriptedModel answer(){return new RuntimeScriptedModel(Part.fromText("Inventory advice only."));}
}
