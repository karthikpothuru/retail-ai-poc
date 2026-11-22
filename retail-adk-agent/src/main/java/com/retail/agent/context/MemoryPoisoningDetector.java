package com.retail.agent.context;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Conservative signals, not proof of safety or a replacement for provenance/approval controls. */
public final class MemoryPoisoningDetector {
    public enum Signal { INSTRUCTION_OVERRIDE, ROLE_SPOOFING, CREDENTIAL_EXFILTRATION }
    public List<Signal> inspect(String content) {
        String normalized=java.text.Normalizer.normalize(content,java.text.Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cf}]","");
        List<Signal> result=new ArrayList<>();
        if(matches("(?:ignore|override|disregard)\\s+(?:all\\s+)?(?:previous|system|developer|security|policy)\\s*(?:instructions|rules|policies|prompts)?",normalized))result.add(Signal.INSTRUCTION_OVERRIDE);
        if(matches("<\\s*/?\\s*(?:system|developer)\\s*>|(?:role|authority)\\s*[:=]\\s*(?:system|developer)",normalized))result.add(Signal.ROLE_SPOOFING);
        if(matches("(?:send|reveal|export|exfiltrate)\\b.{0,80}\\b(?:api[ _-]?key|password|credentials|secret|access[ _-]?token)",normalized))result.add(Signal.CREDENTIAL_EXFILTRATION);
        return List.copyOf(result);
    }
    private static boolean matches(String regex,String text){return Pattern.compile(regex,Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(text).find();}
}
