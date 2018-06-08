package info.deskchan.groovy_support;

import groovy.lang.Closure;
import groovy.lang.Script;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public abstract class Scenario extends Script{

    private Answer answer;
    private static final List<String> defaultHelp = Arrays.asList(ScenarioPlugin.pluginProxy.getString("write-anything"));
    private boolean interrupted = false;

    private final Locker lock = new Locker();

    protected void sendMessage(String tag) {
        ScenarioPlugin.pluginProxy.sendMessage(tag, null);
    }

    protected void sendMessage(String tag, Object data) {
        ScenarioPlugin.pluginProxy.sendMessage(tag, data);
    }

    protected String getString(String key){
        return ScenarioPlugin.pluginProxy.getString(key);
    }

    protected Path getDataDirPath() {
        return ScenarioPlugin.pluginProxy.getDataDirPath();
    }

    protected void log(Object text) {
        ScenarioPlugin.pluginProxy.log(text.toString());
    }

    protected void log(Throwable e) {
        ScenarioPlugin.pluginProxy.log(e);
    }

    protected void alert(String text) {
        ScenarioPlugin.pluginProxy.sendMessage("DeskChan:show-technical", new HashMap(){{
            put("text", text);
        }});
    }
    protected void alert(String name, String text) {
        ScenarioPlugin.pluginProxy.sendMessage("DeskChan:show-technical", new HashMap(){{
            put("name", name);
            put("text", text);
            put("priority", messagePriority);
        }});
    }

    protected void say(String text){
        ScenarioPlugin.pluginProxy.sendMessage("DeskChan:say", new HashMap(){{
            put("text", text);
            put("characterImage", currentSprite);
            put("priority", messagePriority);
            put("skippable", false);
        }}, (sender, data) -> {
            lock.unlock();
        });
        lock.lock();
    }
    protected void requestPhrase(String text){
        ScenarioPlugin.pluginProxy.sendMessage("DeskChan:request-say", text);
    }

    private String currentSprite = "normal";
    protected void sprite(String text){
        currentSprite = text;
        ScenarioPlugin.pluginProxy.sendMessage("gui:set-image", text);
    }

    private int messagePriority = 2000;
    protected int setMessagePriority(int val){
        log("changed");
        return (messagePriority = val);
    }

    protected synchronized String receive(){
        return receive(null);
    }
    protected synchronized String receive(Object helpInfo){
        System.out.println(helpInfo);
        ScenarioPlugin.pluginProxy.sendMessage("DeskChan:request-user-speech",
                helpInfo != null ? helpInfo : defaultHelp,
        (sender, data) -> {
            if (interrupted) {
                ScenarioPlugin.pluginProxy.sendMessage("DeskChan:discard-user-speech", data);
                lock.unlock();
                return;
            }
            if (data instanceof Map)
                answer = new Answer((Map) data);
            else
                answer = new Answer(data.toString());

            lock.unlock();
        });

        lock.lock();
        return answer.text;
    }

    protected synchronized void sleep(long delay){
        Map data = new HashMap();
        data.put("value", delay);
        ScenarioPlugin.pluginProxy.sendMessage("core-utils:notify-after-delay", data, (sender, d) -> {
            lock.unlock();
        });
        lock.lock();
    }

    private boolean whenCycle;
    void when(Object obj, Closure cl) {
        CaseCollector caseCollector = new CaseCollector();
        cl.setDelegate(caseCollector);
        cl.call();

        Function result = caseCollector.execute(obj);
        if (result != null)
            result.apply(obj);
    }
    void whenInput(Closure cl) {
        CaseCollector caseCollector = new CaseCollector();
        cl.setDelegate(caseCollector);
        cl.call();

        List<String> helpInfo = caseCollector.getHelpInfo();
        Object obj;
        while(true){
            obj = receive(helpInfo);
            whenCycle = false;

            Function result = caseCollector.executeByInput(obj);
            if (result != null)
                result.apply(obj);
            if(!whenCycle) break;
            cl.call();
        }
    }

    void again() {
        whenCycle = true;
    }

    private class CaseCollector{

        private Map<Object, Function> matches = new HashMap<>();
        private LinkedList<Object> queue = new LinkedList<>();

        class RegularRule { String rule; RegularRule(String rule){ this.rule = rule; } }

        private void clearQueue(Function action){
            for (Object obj : queue)
                matches.put(obj, action);
            queue.clear();
        }

        void equal(Object obj) {
            queue.add(obj);
        }
        void equal(Boolean obj) {
            if (obj) queue.add(obj);
        }
        void equal(Object[] obj) {
            for (Object o : obj)
                queue.add(o);
        }
        void equal(AbstractCollection<Object> obj) {
            queue.addAll(obj);
        }
        void equal(Object obj, Function action) {
            matches.put(obj, action);
            clearQueue(action);
        }
        void equal(Object[] obj, Function action) {
            for (Object o : obj)
                matches.put(o, action);
            clearQueue(action);
        }
        void equal(AbstractCollection<Object> obj, Function action) {
            for (Object o : obj)
                matches.put(o, action);
            clearQueue(action);
        }
        void equal(Boolean value, Function action){
            if (value){
                matches.put(true, action);
                clearQueue(action);
            }
        }

        void match(String obj) {
            queue.add(new RegularRule(obj));
        }
        void match(String[] obj) {
            for (String o : obj)
                queue.add(new RegularRule(o));
        }
        void match(String[] obj, Function action) {
            for (String o : obj)
                matches.put(new RegularRule(o), action);
            clearQueue(action);
        }
        void match(String obj, Function action) {
            matches.put(new RegularRule(obj), action);
            clearQueue(action);
        }

        void otherwise(Function action) {
            matches.put(false, action);
            clearQueue(action);
        }

        Function execute(Object key) {

            final AtomicReference<Function> action = new AtomicReference<>();
            action.set(matches.get(matches.containsKey(true)));

            if(matches.size() == 0 || action.get() != null) return action.get();

            List<String> rules = new LinkedList<>();

            for(Map.Entry<Object, Function> entry : matches.entrySet()){
                if(key.equals(entry.getKey())){
                    return entry.getValue();
                }
                if(entry.getKey() instanceof RegularRule){
                    rules.add(((RegularRule) entry.getKey()).rule);
                }
            }

            if ((key instanceof String || key instanceof Answer) && rules.size() > 0) {
                ScenarioPlugin.pluginProxy.sendMessage("speech:match-any", new HashMap<String, Object>() {{
                    put("speech", key instanceof Answer ? ((Answer) key).text : key.toString());
                    put("rules", rules);
                }}, (sender, data) -> {
                    Integer i = ((Number) data).intValue();

                    if (i >= 0) {
                        for (Map.Entry<Object, Function> entry : matches.entrySet()) {
                            if (entry.getKey() instanceof RegularRule && ((RegularRule) entry.getKey()).rule.equals(rules.get(i))) {
                                action.set(entry.getValue());
                                break;
                            }
                        }
                    }

                    lock.unlock();
                });
                lock.lock();
            }

            return action.get();
        }

        Function executeByInput(Object key) {

            final AtomicReference<Function> action = new AtomicReference<>(matches.get(false));

            if(matches.size() == 0) return action.get();

            Answer current;
            if (key.toString().equals(answer.toString()))
                current = answer;
            else
                current = new Answer(key.toString());

            List<String> rules = new LinkedList<>();
            int maxVariantPriority = -1, vp;
            Function maxVariant = null;

            for(Map.Entry<Object, Function> entry : matches.entrySet()){
                if(entry.getKey() instanceof String && (vp = current.purpose.indexOf(entry.getKey())) >= 0){
                    if (maxVariantPriority < 0 || maxVariantPriority > vp){
                        maxVariantPriority = vp;
                        maxVariant = entry.getValue();
                    }
                }
                if(entry.getKey() instanceof RegularRule){
                    rules.add(((RegularRule) entry.getKey()).rule);
                }
            }

            if (maxVariant != null) return maxVariant;

            if (rules.size() > 0) {
                ScenarioPlugin.pluginProxy.sendMessage("speech:match-any", new HashMap<String, Object>() {{
                    put("speech", current.text);
                    put("rules", rules);
                }}, (sender, data) -> {
                    Integer i = ((Number) data).intValue();

                    if (i >= 0) {
                        for (Map.Entry<Object, Function> entry : matches.entrySet()) {
                            if (entry.getKey() instanceof RegularRule && ((RegularRule) entry.getKey()).rule.equals(rules.get(i))) {
                                action.set(entry.getValue());
                                break;
                            }
                        }
                    }

                    lock.unlock();
                });
                lock.lock();
            }

            return action.get();
        }

        List<String> getHelpInfo(){
            if (matches.size() == 1 && matches.keySet().iterator().next().equals(false)){
                return null;
            }
            boolean isOtherwise = false;
            List<String> help = new ArrayList<>();
            for (Object an : matches.keySet()){
                if (an instanceof RegularRule)
                    help.add(((RegularRule) an).rule);
                else if (an.equals(false))
                    isOtherwise = true;
                else if (an instanceof String)
                    help.add("~" + ScenarioPlugin.pluginProxy.getString(an.toString()));
            }
            if (isOtherwise)
                help.add(ScenarioPlugin.pluginProxy.getString("other"));
            return help;
        }

    }

    protected void quit(){
        interrupted = true;
        throw new ScenarioPlugin.Companion.InterruptedScenarioException();
    }

    private static class Answer{

        String text;
        List<String> purpose = null;

        Answer(String text){ this.text = text; }
        Answer(Map data){
            this.text = data.get("value") != null ? data.get("value").toString() : "";
            Object p = data.get("purpose");
            if (p == null) return;
            if (p instanceof Collection){
                purpose = new LinkedList<>((Collection) p);
            } else {
                purpose = new LinkedList<>();
                purpose.add(p.toString());
            }
        }

        public String toString(){ return text; }
    }

    private class Locker {

        boolean notified = false;

        public void lock(){
            if (!notified) {
                try {
                    synchronized (this) {
                        this.wait();
                    }
                } catch (InterruptedException e) {
                    quit();
                } catch (Throwable e) {
                    log(e);
                    quit();
                }
            }
            notified = false;
        }

        public void unlock(){
            notified = true;
            synchronized (this) {
                this.notify();
            }
        }
    }

    /** Working with character preset. **/

    public boolean instantCharacterInfluence = false;
    protected class Preset {
        private Map<String, Object> presetMap;
        Preset() {
            update();
        }
        Preset(Map<String, Object> map){
            presetMap = map;
        }

        public void update(){
            ScenarioPlugin.pluginProxy.sendMessage("talk:get-preset", true, (sender, data) ->  {
                presetMap = (Map) data;
                lock.unlock();
            });
            lock.lock();
        }

        public void save(){
            ScenarioPlugin.pluginProxy.sendMessage("talk:save-options", presetMap);
        }

        public Object getField(String key){  return presetMap.get("key");  }
        public void setField(String key, Object value){
            presetMap.put(key, value);
            if (instantCharacterInfluence) save();
        }

        public String getName(){  return (String) presetMap.get("name");  }
        public void setName(String newName){
            setField("name", newName);
            if (instantCharacterInfluence) save();
        }

        public Map getTags(){  return (Map) presetMap.get("tags");  }

        public Map getCharacter(){  return (Map) presetMap.get("character");  }
        public float getCharacterField(String key){  return ((Number) presetMap.get(key)).floatValue();      }
        public float setCharacterField(String key, float val){
            save();
            ScenarioPlugin.pluginProxy.sendMessage("talk:make-character-influence", new HashMap(){{
                put("feature", key);
                put("value", val - getCharacterField(key));
            }});
            update();
            return getCharacterField(key);
        }

        public float getManner(){  return getCharacterField("manner");  }
        public float getEnergy(){  return getCharacterField("energy");  }
        public float getEmpathy(){  return getCharacterField("empathy");  }
        public float getAttitude(){  return getCharacterField("attitude");  }
        public float getExperience(){  return getCharacterField("experience");  }
        public float getImpulsivity(){  return getCharacterField("impulsivity");  }
        public float getRelationship(){  return getCharacterField("relationship");  }

        public float setManner(float val){  return setCharacterField("manner", val);  }
        public float setEnergy(float val){  return setCharacterField("energy", val);  }
        public float setEmpathy(float val){  return setCharacterField("empathy", val);  }
        public float setAttitude(float val){  return setCharacterField("attitude", val);  }
        public float setExperience(float val){  return setCharacterField("experience", val);  }
        public float setImpulsivity(float val){  return setCharacterField("impulsivity", val);  }
        public float setRelationship(float val){  return setCharacterField("relationship", val);  }
        public float setSelfconfidence(float val){  return setCharacterField("selfconfidence", val);  }

        public float getSelfconfidence(){  return getCharacterField("selfconfidence");   }


        public void raiseEmotion(String key, String val){
            ScenarioPlugin.pluginProxy.sendMessage("talk:make-emotion-influence", new HashMap(){{
                put("emotion", key);
                put("value", val);
            }});
            update();
        }

        public String toString(){ return presetMap.toString(); }

    }
    private Preset currentPreset = null;

    public Preset getPreset(){
        if (currentPreset == null) currentPreset = new Preset();
        return currentPreset;
    }
    public Preset setPreset(Map newPreset){
        currentPreset = new Preset(newPreset);
        if (instantCharacterInfluence) currentPreset.save();
        return currentPreset;
    }
}