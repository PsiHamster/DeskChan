package info.deskchan.core;

import java.nio.file.Path;
import java.util.*;


// This plugin contains
//   - Alternatives infrastructure
//   - Fallbacks for some major DeskChan functions
//   - Commands interface initialization
public class CorePlugin implements Plugin, MessageListener {
	
	protected PluginProxyInterface pluginProxy = null;
	protected final Map<String, List<AlternativeInfo>> alternatives = new HashMap<>();

	@Override
	public boolean initialize(PluginProxyInterface pluginProxy) {
		this.pluginProxy = pluginProxy;

		pluginProxy.getProperties().load();
		pluginProxy.getProperties().putIfHasNot("quitDelay", 2000);
		pluginProxy.getProperties().putIfHasNot("locale", Locale.getDefault().getLanguage());
		Locale.setDefault(new Locale(pluginProxy.getProperties().getString("locale")));

		pluginProxy.setResourceBundle("info/deskchan/strings");
        pluginProxy.setConfigField("name", pluginProxy.getString("core-plugin-name"));
		try {
			PluginProxy.Companion.updateResourceBundle();
		} catch (Exception e){
			pluginProxy.log(e);
		}

		CommandsProxy.initialize(pluginProxy);

		/* Change language
        * Public message
        * Params: String! - language key
        * Returns: None */
		pluginProxy.addMessageListener("core:set-language", (sender, tag, data) -> {
			final String newValue;
			if (data instanceof Map)
				newValue = ((Map) data).get("value").toString();
			else
				newValue = data.toString();

			for(Map.Entry<String,String> locale : CoreInfo.locales.entrySet()){
				if(locale.getKey().equals(newValue) || locale.getValue().equals(newValue)){
					Locale.setDefault(new Locale(locale.getKey()));
					pluginProxy.getProperties().put("locale", locale.getKey());
					return;
				}
			}
			pluginProxy.log(new Exception("Unknown language key: " + newValue));
		});


		/* Quit program.
		* Public message
        * Params: delay: Long? - delay in ms program will quit after, default - 0
        * Returns: None */
		pluginProxy.addMessageListener("core:quit", (sender, tag, data) -> {
			int delay = pluginProxy.getProperties().getInteger("quitDelay", 2000);
			if (data != null) {
				if (data instanceof Map) {
					delay = (int) ((Map<String, Object>) data).getOrDefault("delay", delay);
				} else if (data instanceof Number) {
					delay = ((Number) data).intValue();
				}
			} else {
				delay = PluginManager.isDebugBuild() ? 0 : delay;
			}

			Map<String, Object> m = new HashMap<>();
			m.put("delay", delay);
			pluginProxy.log("Plugin " + sender + " requested application quit in " + delay / 1000 + " seconds.");
			pluginProxy.sendMessage("core:save-all-properties", null);
			if(delay > 20) {
				Timer quitTimer = new Timer();
				quitTimer.schedule(new TimerTask() {
					@Override public void run() {
						PluginManager.getInstance().quit();
					}
				}, delay);
			} else {
				PluginManager.getInstance().quit();
			}

		});

		/* Registers alternative to tag. All messages sent to srcTag will be redirected to dstTag
		   if dstTag priority is max.
		* Public message
        * Params: srcTag: String! - source tag to redirect
        *         dstTag: String! - destination tag
        *         priority: String! - priority of alternative
        * Returns: None */
		pluginProxy.addMessageListener("core:register-alternative", (sender, tag, data) -> {
			Map m = (Map) data;
			registerAlternative(m.get("srcTag").toString(), m.get("dstTag").toString(),
					sender, m.get("priority"));
		});

		/* Registers alternatives. Look for core:register-alternative
		* Public message
        * Params: List of Map
        * 		    srcTag: String! - source tag to redirect
        *           dstTag: String! - destination tag
        *           priority: String! - priority of alternative
        * Returns: None */
		pluginProxy.addMessageListener("core:register-alternatives", (sender, tag, data) -> {
			List<Map> alternativeList = (List<Map>) data;
			for (Map m : alternativeList) {
				registerAlternative(m.get("srcTag").toString(), m.get("dstTag").toString(),
						sender, m.get("priority"));
			}
		});

		/* Unregisters alternative. Look for core:register-alternative
		* Public message
        * Params: srcTag: String! - source tag to redirect
        *         dstTag: String! - destination tag
        * Returns: None */
		pluginProxy.addMessageListener("core:unregister-alternative", (sender, tag, data) -> {
			Map m = (Map) data;
			unregisterAlternative(m.get("srcTag").toString(), m.get("dstTag").toString(), sender);
		});

		/* Get alternatives map.
		* Public message
        * Params: None
        * Returns: Map of Lists of Maps, "source" -> "alternatives", every list descending by priority
        *            tag: String - destination tag
        *            plugin: String - owner of destination tag
        *            priority: Int - priority of alternative*/
		pluginProxy.addMessageListener("core:query-alternatives-map", (sender, tag, data) -> {
			pluginProxy.sendMessage(sender, getAlternativesMap());
		});

		/* Clearing all dependencies of unloaded plugin.
		 * Technical message
		 * Params: name: String - name of plugin
		 * Returns: None  */
		pluginProxy.addMessageListener("core-events:plugin-unload", (sender, tag, data) -> {
			if(data == null) {
				PluginManager.log("attempt to unload null plugin");
				return;
			}
			String plugin = data.toString();
			synchronized (this) {
				Iterator < Map.Entry<String, List<AlternativeInfo>> > mapIterator = alternatives.entrySet().iterator();
				while (mapIterator.hasNext()) {
					Map.Entry <String, List<AlternativeInfo>> entry = mapIterator.next();
					List<AlternativeInfo> l = entry.getValue();
					Iterator<AlternativeInfo> iterator = l.iterator();
					while (iterator.hasNext()) {
						AlternativeInfo info = iterator.next();
						if (info.plugin.equals(plugin)) {
							iterator.remove();
							pluginProxy.log("Unregistered alternative " + info.tag + " for tag " + entry.getKey());
						}
					}
					if (l.isEmpty()) {
						String srcTag = entry.getKey();
						pluginProxy.removeMessageListener(srcTag, this);
						pluginProxy.log("No more alternatives for " + srcTag);
						mapIterator.remove();
					}
				}
			}
		});

		pluginProxy.sendMessage("core:add-command", new HashMap<String, Object>(){{
			put("tag", "DeskChan:say");
			put("info", pluginProxy.getString("plugin-unload-info"));
			put("msgInfo", new HashMap<String, String>(){{
				put("text", pluginProxy.getString("text"));
				put("characterImage", pluginProxy.getString("sprite"));
				put("priority", pluginProxy.getString("priority"));
			}});
		}});

		pluginProxy.sendMessage("core:add-command", new HashMap(){{
			put("tag", "DeskChan:commands-list");
			put("info", pluginProxy.getString("commands-list-info"));
		}});

		/* Get plugin data directory.
		 * Public message
		 * Params: None
		 * Returns: String - path to directory  */
		pluginProxy.addMessageListener("core:get-plugin-data-dir", (sender, tag, data) -> {
			Path pluginDataDirPath = PluginManager.getPluginDataDirPath(sender);
			pluginProxy.sendMessage(sender, pluginDataDirPath.toString());
		});

		/* Catch logging from other plugins.
		 * Public message
		 * Params: level: LoggerLevel
		 *         message: String!
		 * Returns: None  */
		pluginProxy.addMessageListener("core-events:log", (sender, tag, data) -> {
			Map mapLog = (Map) data;
			LoggerLevel level = (LoggerLevel) mapLog.getOrDefault("level",LoggerLevel.INFO);
			PluginManager.log(sender, (String) mapLog.get("message"),level);
		});

		/* Catch exceptions from other plugins.
		 * Public message
		 * Params: class: String!
		 *         message: String!
		 *         stacktrace: List<String>
		 * Returns: None  */
		pluginProxy.addMessageListener("core-events:error", (sender, tag, data) -> {
			Map error = (Map) data;
			String message = (error.get("class") != null ? error.get("class") : "") +
					         (error.get("message") != null ? ": " + error.get("message") : "");
			PluginManager.log(sender, message , (List) error.get("stacktrace"));
		});

		/* Asks all plugins to save their properties on disk
		 * Public message
		 * Params: None
		 * Returns: None  */
		pluginProxy.addMessageListener("core:save-all-properties", (sender, tag, data) -> {
			PluginManager.getInstance().saveProperties();
		});

		pluginProxy.sendMessage("core:register-alternatives", Arrays.asList(
				new HashMap<String, Object>() {{
					put("srcTag", "DeskChan:voice-recognition");
					put("dstTag", "DeskChan:user-said");
					put("priority", 50);
				}},
				new HashMap<String, Object>() {{
					put("srcTag", "DeskChan:user-said");
					put("dstTag", "core:inform-no-speech-function");
					put("priority", 1);
				}},
				new HashMap<String, Object>() {{
					put("srcTag", "DeskChan:notify");
					put("dstTag", "core:notify");
					put("priority", 1);
				}}
		));

		pluginProxy.addMessageListener("core:inform-no-speech-function", (sender, tag, data) -> {
			pluginProxy.sendMessage("DeskChan:say", pluginProxy.getString("no-conversation"));
		});

		pluginProxy.addMessageListener("core:notify", (sender, tag, data) -> {
			Map ntf = (Map) data;
			if (ntf.containsKey("message"))
				pluginProxy.sendMessage("DeskChan:show-technical", new HashMap(){{
					put("text", ntf.get("message"));
				}});
			if (ntf.containsKey("speech"))
				pluginProxy.sendMessage("DeskChan:say", new HashMap(){{
					put("text", ntf.get("speech"));
					if (ntf.containsKey("priority"))
						put("priority", ntf.get("priority"));
				}});
			else
				pluginProxy.sendMessage("DeskChan:request-say", new HashMap(){{
					put("purpose", ntf.getOrDefault("speech-purpose", "NOTIFY"));
					if (ntf.containsKey("priority"))
						put("priority", ntf.get("priority"));
				}});

		});

		return true;
	}

	private void registerAlternative(String srcTag, String dstTag, String plugin, Object priority) {
		int _priority;
		if (priority instanceof Number)
			_priority = ((Number) priority).intValue();
		else {
			try {
				_priority = Integer.parseInt(priority.toString());
			} catch (Exception e){
				throw new ClassCastException(
						"Cannot cast '" + priority.toString() + "', type=" + priority.getClass() + "  to integer");
			}
		}

		List<AlternativeInfo> list = alternatives.get(srcTag);
		if (list == null) {
			list = new LinkedList<>();
			alternatives.put(srcTag, list);
			pluginProxy.addMessageListener(srcTag, this);
			pluginProxy.addMessageListener(srcTag+"#", this);
		}

		Iterator<AlternativeInfo> iterator = list.iterator();
		while (iterator.hasNext()) {
			AlternativeInfo info = iterator.next();
			if (info.isEqual(dstTag, plugin)) {
				iterator.remove();
				break;
			}
		}

		list.add(new AlternativeInfo(dstTag, plugin, _priority));
		list.sort(new Comparator<AlternativeInfo>() {
			@Override
			public int compare(AlternativeInfo o1, AlternativeInfo o2) {
				return Integer.compare(o2.priority, o1.priority);
			}
		});

		pluginProxy.log("Registered alternative " + dstTag + " for tag " + srcTag + " with priority: " + priority + ", by plugin " + plugin);
	}
	
	private void unregisterAlternative(String srcTag, String dstTag, String plugin) {
		List<AlternativeInfo> list = alternatives.get(srcTag);
		if (list == null)
			return;

		Iterator<AlternativeInfo> iterator = list.iterator();
		while (iterator.hasNext()) {
			AlternativeInfo info = iterator.next();
			if (info.isEqual(dstTag, plugin)) {
				iterator.remove();
				pluginProxy.log("Unregistered alternative " + dstTag + " for tag " + srcTag);
				break;
			}
		}

		if (list.isEmpty()) {
			alternatives.remove(srcTag);
			pluginProxy.removeMessageListener(srcTag, this);
			pluginProxy.removeMessageListener(srcTag+"#", this);
			pluginProxy.log("No more alternatives for " + srcTag);
		}
	}

	private Map<String, Object> getAlternativesMap() {
		Map<String, Object> m = new HashMap<>();
		for (Map.Entry<String, List<AlternativeInfo>> entry : alternatives.entrySet()) {
			List<Map<String, Object>> l = new ArrayList<>();
			for (AlternativeInfo info : entry.getValue()) {
				l.add(new HashMap<String, Object>() {{
					put("tag", info.tag);
					put("plugin", info.plugin);
					put("priority", info.priority);
				}});
			}
			m.put(entry.getKey(), l);
		}
		return m;
	}

	@Override
	public void handleMessage(String sender, String tag, Object data) {
		int delimiter = tag.indexOf("#");
		String senderTag = null;
		if (delimiter > 0) {
			senderTag = tag.substring(delimiter + 1);
			tag = tag.substring(0, delimiter);
		}

		List<AlternativeInfo> list = alternatives.get(tag);
		if (list == null || list.isEmpty())
			return;

		Iterator<AlternativeInfo> iterator = list.iterator();
		if (senderTag != null){
			do {
				if (!iterator.hasNext()) return;
				AlternativeInfo nextInfo = iterator.next();
				if (nextInfo.tag.equals(senderTag)) break;
			} while (true);
		}

		try {
			AlternativeInfo info = iterator.next();
			PluginManager.getInstance().sendMessage(sender, info.tag, data);
		} catch (NoSuchElementException e) { }
	}

	@Override
	public void unload(){
		pluginProxy.getProperties().save();
	}

	static class AlternativeInfo {
		String tag;
		String plugin;
		int priority;
		
		AlternativeInfo(String tag, String plugin, int priority) {
			this.tag = tag;
			this.plugin = plugin;
			this.priority = priority;
		}

		boolean isEqual(String tag, String plugin){
			return this.tag.equals(tag) && this.plugin.equals(plugin);
		}

		@Override
		public String toString(){
			return tag + "(" + plugin + ")" + "=" + priority;
		}
	}
}
