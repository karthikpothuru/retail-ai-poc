package com.retail.agent.context;

import java.util.*;

/** Deliberately closed, flat-string JSON Schema subset. Unsupported shapes cannot be registered. */
public record McpInputSchema(Map<String,Field> fields) {
    public record Field(boolean required,int maximumLength,String pattern,Set<String> values){
        public Field{if(maximumLength<1||maximumLength>256)throw new IllegalArgumentException("Invalid schema bound");values=Set.copyOf(values);if(pattern!=null)java.util.regex.Pattern.compile(pattern);}
    }
    public McpInputSchema {fields=Map.copyOf(fields);if(fields.size()>16)throw new IllegalArgumentException("Too many arguments");
        fields.keySet().forEach(k->{ContextIdentity.identifier(k);if(Set.of("tenantid","userid","sessionid","taskid","authorization","token","identity").contains(k.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Identity arguments forbidden");});}
    public Map<String,Object> json(){
        var properties=new TreeMap<String,Object>();var required=new ArrayList<String>();
        fields.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->{var f=e.getValue();var spec=new LinkedHashMap<String,Object>();spec.put("type","string");spec.put("maxLength",f.maximumLength());if(f.pattern()!=null)spec.put("pattern",f.pattern());if(!f.values().isEmpty())spec.put("enum",f.values().stream().sorted().toList());properties.put(e.getKey(),spec);if(f.required())required.add(e.getKey());});
        return Map.of("type","object","properties",properties,"required",required,"additionalProperties",false);
    }
    public Map<String,Object> validate(Map<String,Object> input){
        if(input==null||!fields.keySet().containsAll(input.keySet()))throw new SecurityException("Unexpected MCP argument");
        var copy=new TreeMap<String,Object>();
        for(var entry:fields.entrySet()){
            var f=entry.getValue();Object value=input.get(entry.getKey());
            if(value==null){if(f.required()||input.containsKey(entry.getKey()))throw new SecurityException("Missing MCP argument");continue;}
            if(!(value instanceof String s)||s.length()>f.maximumLength()||(f.pattern()!=null&&!s.matches(f.pattern()))||(!f.values().isEmpty()&&!f.values().contains(s)))throw new SecurityException("Invalid MCP argument");
            copy.put(entry.getKey(),s);
        }
        return Map.copyOf(copy);
    }
}
