sendMessage("core:register-alternative", [
        "srcTag": "DeskChan:request-say",
        "dstTag": "advices:replace-request",
        "priority": 15000
])

addMessageListener("advices:replace-request", { s,t,d ->
    if (d != null && new Random().nextFloat() > 0.85) {
        if (d instanceof Map && d.get("purpose").toString().toUpperCase() == "CHAT") {
            d.put("purpose", "ADVICE")
        } else if (d.toString().toUpperCase() == "CHAT"){
            d = "ADVICE"
        }
    }
    sendMessage("DeskChan:request-say#advices:replace-request", d)
})

sendMessage("core:add-command", [
        tag: 'advices:get',
        info: getString('get-advice')
])

sendMessage("core:set-event-link", [
        "eventName": "gui:menu-action",
        "commandName": "advices:get",
        "rule": getString('get-advice')
])

addMessageListener("advices:get", { s, t, d ->
    sendMessage("DeskChan:request-say", "ADVICE")
})

setConfigField("name", [
        "ru": "Советы",
        "en": "Advices"
])