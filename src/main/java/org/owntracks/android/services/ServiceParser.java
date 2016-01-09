package org.owntracks.android.services;

import android.content.Intent;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.StyleSpan;
import android.util.Log;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.android.gms.location.Geofence;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.owntracks.android.App;
import org.owntracks.android.R;
import org.owntracks.android.db.Dao;
import org.owntracks.android.db.MessageDao;
import org.owntracks.android.messages.MessageBase;
import org.owntracks.android.messages.MessageCard;
import org.owntracks.android.messages.MessageCmd;
import org.owntracks.android.messages.MessageLocation;
import org.owntracks.android.messages.MessageMsg;
import org.owntracks.android.messages.MessageTransition;
import org.owntracks.android.messages.MessageUnknown;
import org.owntracks.android.model.FusedContact;
import org.owntracks.android.support.Events;
import org.owntracks.android.support.GeocodingProvider;
import org.owntracks.android.support.IncomingMessageProcessor;
import org.owntracks.android.support.Preferences;

import java.util.Date;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ServiceParser implements ProxyableService, IncomingMessageProcessor {
    public static final String TAG = "ServiceParser";
    ObjectMapper mapper;
    ServiceProxy context;
    private ThreadPoolExecutor pool;

    @Override
    public void processMessage(MessageBase message) {
        Log.v(TAG, "processMessage MessageBase (" + message.getTopic()+")");
    }

    public void processMessage(MessageUnknown message) {
        Log.v(TAG, "processMessage MessageUnknown (" + message.getTopic()+")");
    }


    @Override
    public void processMessage(MessageLocation message) {
        Log.v(TAG, "processMessage MessageLocation (" + message.getTopic()+")");

        GeocodingProvider.resolve(message);
        FusedContact c = App.getFusedContact(message.getTopic());

        if (c == null) {
            c = new FusedContact(message.getTopic());
            c.setMessageLocation(message);
            App.addFusedContact(c);
        } else {
            c.setMessageLocation(message);
        }




    }

    @Override
    public void processMessage(MessageCard message) {
        Log.v(TAG, "processMessage MessageCard (" + message.getTopic() + ")");
        FusedContact c = App.getFusedContact(message.getTopic());

        if (c == null) {
            c = new FusedContact(message.getTopic());
            c.setMessageCard(message);
            App.addFusedContact(c);
        } else {
            c.setMessageCard(message);
        }
    }

    @Override
    public void processMessage(MessageCmd message) {
        Log.v(TAG, "processMessage MessageCmd (" + message.getTopic()+")");
    }

    @Override
    public void processMessage(MessageTransition message) {
        Log.v(TAG, "processMessage MessageTransition (" + message.getTopic() + ")");
        ServiceProxy.getServiceNotification().processMessage(message);
    }

    @Override
    public void processMessage(MessageMsg message) {
        Log.v(TAG, "processMessage MessageMsg (" + message.getTopic() + ")");

        String externalId = message.getTopic() + "$" + message.getTst();

        org.owntracks.android.db.Message m = Dao.getMessageDao().queryBuilder().where(MessageDao.Properties.ExternalId.eq(externalId)).unique();
        if (m == null) {
            m = new org.owntracks.android.db.Message();
            m.setIcon(message.getIcon());
            m.setPriority(message.getPrio());
            m.setIcon(message.getIcon());
            m.setIconUrl(message.getIconUrl());
            m.setUrl(message.getUrl());
            m.setExternalId(externalId);
            m.setDescription(message.getDesc());
            m.setTitle(message.getTitle());
            m.setTst(message.getTst());
            if (message.getMttl() != 0)
                m.setExpiresTst(message.getTst() + message.getMttl());

            if (message.getTopic().equals(Preferences.getBroadcastMessageTopic()))
                m.setChannel("broadcast");
            else if (message.getTopic().startsWith(Preferences.getPubTopicBase(true)))
                m.setChannel("direct");
            else
                try {
                    m.setChannel(message.getTopic().split("/")[1]);
                } catch (IndexOutOfBoundsException exception) {
                    m.setChannel("undefined");
                }

            Dao.getMessageDao().insert(m);
            ServiceProxy.getServiceNotification().processMessage(message);
        }
    }

    public void onCreate(ServiceProxy c) {
        this.mapper = new ObjectMapper();
        this.context = c;
        pool= new ThreadPoolExecutor(2,2,1,  TimeUnit.MINUTES,new LinkedBlockingQueue<Runnable>());

    }

    @Override
    public void onDestroy() {

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return 0;
    }

    @Override
    public void onEvent(Events.Dummy event) {

    }

    private String getBaseTopic(MessageBase message, String topic){

        if (message.getBaseTopicSuffix() != null && topic.endsWith(message.getBaseTopicSuffix())) {
            return topic.substring(0, (topic.length() - message.getBaseTopicSuffix().length()));
        } else {
            return topic;
        }
    }

    public void fromJSON(String topic, MqttMessage message) throws Exception {
        try {
            MessageBase m = mapper.readValue(message.getPayload(), MessageBase.class);
            m.setTopic(getBaseTopic(m, topic));
            m.setRetained(message.isRetained());
            m.setQos(message.getQos());
            m.setIncomingProcessor(this);


            pool.execute(m);

        } catch (Exception e) {

            Log.e(TAG, "JSON parser exception for message: " + new String(message.getPayload()));
            Log.e(TAG, e.getMessage() +" " + e.getCause());

            e.printStackTrace();
        }
    }

    public String toJSON(MessageBase m) throws JsonProcessingException {
        return mapper.writeValueAsString(m);
    }


    public void parseIncomingBrokerMessage(String topic, MqttMessage message) throws Exception {
        fromJSON(topic, message);
    }

    public void onEventMainThread(Events.ClearLocationMessageReceived e) {
        App.removeContact(e.getContact());
    }






    private String getBaseTopic(String forStr, String topic) {
        if (topic.endsWith(forStr))
            return topic.substring(0, (topic.length() - forStr.length()));
        else
            return topic;
    }

    public String getBaseTopicForEvent(String topic) {
        return getBaseTopic(Preferences.getPubTopicEventsPart(), topic);
    }

    //TODO
    public void onEventMainThread(Events.ConfigurationMessageReceived e) {

        Preferences.fromJsonObject(e.getConfigurationMessage().toJSONObject());

        // Reconnect to broker after new configuration has been saved.
        Runnable r = new Runnable() {

            @Override
            public void run() {
                ServiceProxy.getServiceBroker().reconnect();
            }
        };
        new Thread(r).start();

    }

}