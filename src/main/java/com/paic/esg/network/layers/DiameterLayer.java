package com.paic.esg.network.layers;

import com.paic.esg.api.chn.ChannelMessage;
import com.paic.esg.api.network.LayerInterface;
import com.paic.esg.api.settings.LayerSettingsInterface;
import com.paic.esg.helpers.ExtendedResource;
import com.paic.esg.impl.chn.ChannelHandler;
import com.paic.esg.impl.settings.diameter.DiameterSettings;
import org.jdiameter.api.*;
import org.jdiameter.api.EventListener;
import org.jdiameter.client.api.IMessage;
import org.jdiameter.client.api.IRequest;
import org.jdiameter.client.api.controller.IRealm;
import org.jdiameter.client.api.controller.IRealmTable;
import org.jdiameter.client.impl.controller.RealmImpl;
import org.jdiameter.server.impl.NetworkImpl;
import org.jdiameter.server.impl.StackImpl;
import org.jdiameter.server.impl.helpers.XMLConfiguration;
import org.mobicents.diameter.dictionary.AvpDictionary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;

import static org.jdiameter.client.impl.helpers.Parameters.OwnDiameterURI;

public class DiameterLayer implements LayerInterface, NetworkMsgListener, NetworkReqListener, EventListener<Request, Answer> {

    private static final Logger logger = LoggerFactory.getLogger(DiameterLayer.class);

    private LayerSettingsInterface diameterSettings;
    private StackImpl diameterStack;
    private ChannelHandler channelHandler;

    @Override
    public Answer processRequest(Request request) {

        logger.info(String.format("<< Diameter received request for sessionId [%s]", request.getSessionId()));

        ChannelMessage channelMessage = new ChannelMessage("DMR");
        channelMessage.setParameter("REQUEST", request);

        channelHandler.receiveMessageRequest(channelMessage);

        return null;
    }

    @Override
    public Message processMessage(Message message) {
        IMessage iMessage = (IMessage) message;
        if (iMessage.isRequest()) {
            IRequest req = iMessage;
            Avp destRealmAvp = req.getAvps().getAvp(Avp.DESTINATION_REALM);
            String destRealm;
            if (destRealmAvp == null) {
                return message;
            } else {
                try {
                    destRealm = destRealmAvp.getDiameterIdentity();
                } catch (AvpDataException ade) {
                    return message;
                }
            }
            try {
                IRealmTable realmTable = ((NetworkImpl) diameterStack.unwrap(Network.class)).getRealmTable();
                if (!realmTable.realmExists(destRealm)) {
                    IRealm realm = new RealmImpl(destRealm, iMessage.getSingleApplicationId(), LocalAction.LOCAL,
                            null, null, true, 1, new String[]{});
                    addRealms(realm);
                    channelHandler.onReceiveUnknownRealm(realm);
                } else {
                    Collection<Realm> realms = realmTable.getRealms(destRealm);
                    if (!realms.stream().anyMatch(realm -> realm.getApplicationId().equals(iMessage.getSingleApplicationId()))) {
                        IRealm realm = new RealmImpl(destRealm, iMessage.getSingleApplicationId(), LocalAction.LOCAL,
                                null, null, true, 1, new String[]{});
                        addRealms(realm);
                        channelHandler.onReceiveUnknownRealm(realm);
                    }
                }
            } catch (InternalException e) {
                logger.error("Exception caught", e);
            }
        }
        return message;
    }

    public DiameterLayer(DiameterSettings diameterSettings) throws Exception {
        this.diameterSettings = diameterSettings;

        try {
            InputStream inputStreamDictionary = new ExtendedResource(diameterSettings.getDictionary()).getAsStream();
            AvpDictionary.INSTANCE.parseDictionary(inputStreamDictionary);

            diameterStack = new StackImpl();

            InputStream inputStreamXmlConfiguration = new ExtendedResource(diameterSettings.getConfig()).getAsStream();
            diameterStack.init(new XMLConfiguration(inputStreamXmlConfiguration));

            Thread.sleep(500L);

            diameterStack.start();

            Network network = diameterStack.unwrap(Network.class);

            Set<ApplicationId> stackAppIds = diameterStack.getMetaData().getLocalPeer().getCommonApplications();
            for (ApplicationId appId : stackAppIds) {
                logger.info("Adding diameter support for " + appId);
                network.addNetworkReqListener(this, new ApplicationId[]{appId});
            }
        } catch (Exception e) {
            // TODO log error
            logger.error("Exception caught", e);
        }
    }

    @Override
    public void setChannelHandler(ChannelHandler channelHandler) {
        this.channelHandler = channelHandler;
    }

    @Override
    public String getName() {
        return diameterSettings.getName();
    }

    @Override
    public void stop() {
        try {
            diameterStack.stop(DisconnectCause.REBOOTING);
            logger.info("DiameterLayer has been stopped");
        } catch (Exception e) {
            logger.error("Exception when stopping DiameterLayer ('" + getName() + "'). " + e);
        }
    }

    @Override
    public LayerSettingsInterface getSetting() {
        return diameterSettings;
    }

    public Session getSession(String... sessionId) {
        Session session = null;
        try {
            synchronized (this) {
                if (sessionId != null) {
                    logger.debug(String.format("getSession(%s)", sessionId[0]));
                    session = diameterStack.getSession(sessionId[0], Session.class);
                }
                if (session == null) {
                    session = diameterStack.getSessionFactory().getNewSession(sessionId[0]);
                }
            }
        } catch (Exception e) {
            logger.error("Caught exception while creating a new raw diameter session!", e);
        }
        return session;
    }


    public void sendMessage(Session session, Message message, Boolean routeRecord, Boolean useRealmToRoute) throws InternalException, RouteException, OverloadException, IllegalDiameterStateException {
        logger.info(String.format(">> Diameter sending message for sessionId [%s]", message.getSessionId()));
        if (routeRecord != null && routeRecord && message.isRequest()) addRouteRecord(message);
        session.send(message, this, useRealmToRoute);
    }


    @Override
    public void receivedSuccessMessage(Request request, Answer answer) {
        logger.info(String.format("<< Diameter received answer for request sessionId [%s] and answer sessionId [%s]", request.getSessionId(), answer.getSessionId()));
        ChannelMessage channelMessage = new ChannelMessage("DMA");
        channelMessage.setParameter("REQUEST", request);
        channelMessage.setParameter("ANSWER", answer);

        channelHandler.receiveMessageRequest(channelMessage);
    }

    @Override
    public void timeoutExpired(Request request) {
        logger.warn(String.format("== Diameter timeout for sessionId [%s]", request == null ? "null" : request.getSessionId()));
        try {
            diameterStack.getSession(request.getSessionId(), Session.class).release();
        } catch (Exception e) {
        }
    }

    private void addRouteRecord(Message message) {
        String diameterUri = diameterStack.getConfiguration().getStringValue(OwnDiameterURI.ordinal(), "");
        Avp record = message.getAvps().getAvp(Avp.ROUTE_RECORD);
        if (record == null)
            message.getAvps().addAvp(Avp.ROUTE_RECORD, diameterUri, true, false, true);
    }

    public void addRealms(IRealm... realms) throws InternalException {
        IRealmTable realmTable = ((NetworkImpl) diameterStack.unwrap(Network.class)).getRealmTable();
        for (IRealm realm : realms) {
            realmTable.addRealm(realm.getName(), realm.getApplicationId(),
                    realm.getLocalAction(), (String) null, realm.isDynamic(),
                    realm.getExpirationTime(), realm.getPeerNames());
        }
    }

    public List<Peer> getPeerList() {
        List<Peer> peerList = new ArrayList<>();
        try {
            peerList = diameterStack.unwrap(MutablePeerTable.class).getPeerTable();
        } catch (Exception ex) {
            logger.error("Error on get peer list -> " + ex.getMessage());
        }
        return peerList;

    }

    public void addPeer(HashMap<String, Object> paramsData) {
        try {
            URI uri = new URI(paramsData.get("peerURI").toString());
            String ip = (paramsData.get("ip").toString().isEmpty()) ? null : (paramsData.get("ip").toString());
            Peer peer = diameterStack.unwrap(MutablePeerTable.class).addPeer(uri, paramsData.get("realm").toString(),
                    Boolean.parseBoolean(paramsData.get("connecting").toString()), ip);
        } catch (Exception ex) {
            logger.error("Error on try to add a Peer -> " + ex.getMessage());
        }
    }

    public void stopPeer(HashMap<String, Object> paramsData) {
        try {
            Peer peer = diameterStack.unwrap(MutablePeerTable.class).removePeer(paramsData.get("peerName").toString(),
                    Integer.parseInt(paramsData.get("disconnectCause").toString()), Boolean.parseBoolean(paramsData.get("connecting").toString()));
        } catch (Exception ex) {
            logger.error("Error on try to stop Peer -> " + ex.getMessage());
        }
    }
}
