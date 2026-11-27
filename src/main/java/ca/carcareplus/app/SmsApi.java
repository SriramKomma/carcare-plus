package ca.carcareplus.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ca.carcareplus.dispatch.Repairer;
import ca.carcareplus.dispatch.RepairerDirectory;
import ca.carcareplus.dispatch.SmsDispatcher;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.sms.SimulatedSmsGateway;
import ca.carcareplus.sms.TwilioSmsGateway;

/**
 * The SMS side of the live dispatch demo. Like WebApi, no HTTP in here.
 *
 * <pre>
 * POST /api/sms/incoming        From, Body (+ Twilio's other fields)   real SMS replies, from Twilio
 *                               checked with the X-Twilio-Signature header
 * GET  /api/sms/phone           repairer=sriram &amp; since=17   a pretend phone's messages (simulated mode)
 * POST /api/sms/phone/reply     repairer=sriram, text=ACCEPTED   a reply typed on a pretend phone
 * </pre>
 */
public final class SmsApi {

    static final int MAX_REPLY = 160;

    private final SmsDispatcher dispatcher;
    private final RepairerDirectory repairers;

    public SmsApi(SmsDispatcher dispatcher, RepairerDirectory repairers) {
        if (dispatcher == null || repairers == null) {
            throw new InvalidInputException("SmsApi needs the SMS dispatcher and the repairers.");
        }
        this.dispatcher = dispatcher;
        this.repairers = repairers;
    }

    /**
     * A reply from a real phone, forwarded by Twilio.
     *
     * @param signature the X-Twilio-Signature header
     * @return false if the request did not really come from Twilio (it is then ignored)
     */
    public boolean incoming(String signature, Map<String, String> form) {
        if (!(dispatcher.gateway() instanceof TwilioSmsGateway twilio)) {
            return false; // no Twilio account: nothing real can arrive
        }
        if (!twilio.isGenuine(signature, form)) {
            return false;
        }
        dispatcher.received(form.getOrDefault("From", ""), form.getOrDefault("Body", ""));
        return true;
    }

    /** GET /api/sms/phone -- {changed:false} when no new message, else the whole conversation. */
    public Map<String, Object> phone(Map<String, String> params) {
        SimulatedSmsGateway phones = phones();
        Repairer me = repairer(params.get("repairer"));
        long version = phones.version();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("version", version);
        answer.put("now", System.currentTimeMillis());
        if (String.valueOf(version).equals(params.getOrDefault("since", "").trim())) {
            answer.put("changed", false);
            return answer;
        }
        answer.put("changed", true);
        Map<String, Object> owner = new LinkedHashMap<>();
        owner.put("id", me.id());
        owner.put("name", me.name());
        owner.put("phone", me.phone());
        answer.put("me", owner);
        List<Map<String, Object>> messages = new ArrayList<>();
        for (SimulatedSmsGateway.Message message : phones.conversation(me.phone())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fromCarCare", message.fromCarCare());
            item.put("text", message.text());
            item.put("at", message.at());
            messages.add(item);
        }
        answer.put("messages", messages);
        return answer;
    }

    /** POST /api/sms/phone/reply -- goes through exactly the same code as a real SMS reply. */
    public Map<String, Object> reply(Map<String, String> params) {
        SimulatedSmsGateway phones = phones();
        Repairer me = repairer(params.get("repairer"));
        String text = params.getOrDefault("text", "").replaceAll("\\p{Cntrl}", " ").trim();
        if (text.isEmpty()) {
            throw new InvalidInputException("Type a reply first.");
        }
        if (text.length() > MAX_REPLY) {
            throw new InvalidInputException("A text message is at most " + MAX_REPLY + " characters here.");
        }
        phones.received(me.phone(), text);
        String answer = dispatcher.received(me.phone(), text);
        return Map.of("ok", true, "answer", answer);
    }

    private SimulatedSmsGateway phones() {
        if (!(dispatcher.gateway() instanceof SimulatedSmsGateway phones)) {
            throw new InvalidInputException("Real SMS is on (Twilio), so there are no pretend phones: use a real phone.");
        }
        return phones;
    }

    private Repairer repairer(String id) {
        Repairer repairer = repairers.get(id);
        if (repairer == null) {
            throw new InvalidInputException("Unknown repairer '" + (id == null ? "" : id.trim()) + "'.");
        }
        return repairer;
    }
}
