package won.payment.paypal.bot.action.proposal;

import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.rdf.model.impl.ResourceImpl;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RSS;

import com.paypal.svcs.types.ap.PayRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import won.bot.framework.eventbot.EventListenerContext;
import won.bot.framework.eventbot.action.BaseEventBotAction;
import won.bot.framework.eventbot.event.Event;
import won.bot.framework.eventbot.event.impl.analyzation.agreement.ProposalAcceptedEvent;
import won.bot.framework.eventbot.event.impl.command.connectionmessage.ConnectionMessageCommandEvent;
import won.bot.framework.eventbot.event.impl.command.connectionmessage.ConnectionMessageCommandResultEvent;
import won.bot.framework.eventbot.event.impl.command.connectionmessage.ConnectionMessageCommandSuccessEvent;
import won.bot.framework.eventbot.filter.impl.CommandResultFilter;
import won.bot.framework.eventbot.listener.EventListener;
import won.bot.framework.eventbot.listener.impl.ActionOnFirstEventListener;
import won.payment.paypal.bot.impl.PaypalBot;
import won.payment.paypal.bot.impl.PaypalBotContextWrapper;
import won.payment.paypal.bot.model.PaymentContext;
import won.payment.paypal.bot.model.PaymentModelWrapper;
import won.payment.paypal.bot.model.PaymentStatus;
import won.payment.paypal.service.impl.PaypalPaymentService;
import won.protocol.agreement.AgreementProtocolState;
import won.protocol.model.Connection;
import static won.protocol.util.RdfUtils.findOrCreateBaseResource;
import static won.protocol.util.WonRdfUtils.MessageUtils;
import won.protocol.vocabulary.WONMOD;
import won.protocol.vocabulary.WONPAY;

/**
 * After a proposal got accepted this Action will be invoked.
 * 
 * @author schokobaer
 */
public class ProposalAcceptedAction extends BaseEventBotAction {
    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    public ProposalAcceptedAction(EventListenerContext eventListenerContext) {
        super(eventListenerContext);
    }

    @Override
    protected void doRun(Event event, EventListener executingListener) throws Exception {
        if (event instanceof ProposalAcceptedEvent) {
            EventListenerContext ctx = getEventListenerContext();
            ProposalAcceptedEvent proposalAcceptedEvent = (ProposalAcceptedEvent) event;
            Connection con = proposalAcceptedEvent.getCon();
            PaymentContext payCtx = ((PaypalBotContextWrapper) ctx.getBotContextWrapper())
                    .getPaymentContext(con.getAtomURI());
            if (payCtx.getStatus() == PaymentStatus.BUILDING) {
                // Proposed paymodel was accepted
                generatePP(proposalAcceptedEvent);
            } else if (payCtx.getStatus() == PaymentStatus.GENERATED) {
                // Generated Paypal payment was accepted
                // TODO: offer to inject the link into another conversation
            } else if (payCtx.getStatus() == PaymentStatus.PP_DENIED) {
                // Generated Paypal payment was accepted
                // TODO: Implement
                cancelPaymodel(proposalAcceptedEvent);
            }
        }
    }

    /**
     * Sets payCtx.status to building and retracts the payment summary message from
     * the cancellation. TODO: make behaviour more intuitive by not requiring the
     * user to retract earlier messages.
     * 
     * @param proposalAcceptedEvent
     */
    private void cancelPaymodel(ProposalAcceptedEvent proposalAcceptedEvent) {
        Connection con = proposalAcceptedEvent.getCon();
        EventListenerContext ctx = getEventListenerContext();
        PaypalBotContextWrapper botCtx = (PaypalBotContextWrapper) ctx.getBotContextWrapper();
        PaymentContext payCtx = botCtx.getPaymentContext(con.getAtomURI());
        payCtx.setStatus(PaymentStatus.BUILDING);
        botCtx.setPaymentContext(con.getAtomURI(), payCtx);
        AgreementProtocolState state = AgreementProtocolState.of(con.getConnectionURI(), ctx.getLinkedDataSource());
        Model conversation = state.getConversationDataset().getUnionModel();
        StmtIterator itr = conversation.listStatements(null, RDF.type, WONPAY.PAYMENT_SUMMARY);
        String paymentSummaryUri = null;
        while (itr.hasNext()) {
            Resource subj = itr.next().getSubject();
            if (!conversation.listStatements(null, WONMOD.retracts, subj).hasNext()) {
                paymentSummaryUri = subj.getURI();
            }
        }
        if (paymentSummaryUri == null) {
            return;
        }
        try {
            Model retractResponse = MessageUtils.retractsMessage(new URI(paymentSummaryUri));
            retractResponse = MessageUtils.addMessage(retractResponse,
                    "To suggest a new paymodel, the message containing the old paymodel needs to be retracted.");
            ctx.getEventBus().publish(new ConnectionMessageCommandEvent(con, retractResponse));
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }

    /**
     * Generates the PP, sends the link and paykey, and proposes this message.
     * 
     * @context Merchant.
     * @param event the accepted paymodel event.
     */
    private void generatePP(ProposalAcceptedEvent event) {
        Connection con = event.getCon();
        EventListenerContext ctx = getEventListenerContext();
        PaypalBotContextWrapper botCtx = (PaypalBotContextWrapper) ctx.getBotContextWrapper();
        PaymentContext payCtx = botCtx.getPaymentContext(con.getAtomURI());
        payCtx.setStatus(PaymentStatus.PAYMODEL_ACCEPTED);
        botCtx.setPaymentContext(con.getAtomURI(), payCtx);
        // TODO: Send info that a payment is getting generated rn
        PaymentModelWrapper paymodel = new PaymentModelWrapper(event.getPayload());
        try {
            PayRequest pay = paymodel.toPayRequest();
            PaypalPaymentService paypalService = botCtx.getPaypalService();
            String payKey = paypalService.create(pay);
            logger.info("Paypal Payment generated with payKey={}", payKey);
            String url = paypalService.getPaymentUrl(payKey);
            payCtx.setStatus(PaymentStatus.GENERATED);
            payCtx.setPayKey(payKey);
            botCtx.setPaymentContext(con.getAtomURI(), payCtx);
            // Send and propose payment link
            Model response = MessageUtils.processingMessage("Generated PayPal payment: \n" + url);
            findOrCreateBaseResource(response).addProperty(RSS.link, new ResourceImpl(url));
            findOrCreateBaseResource(response).addProperty(WONPAY.HAS_PAYPAL_PAYKEY, payKey);
            final ConnectionMessageCommandEvent responseEvent = new ConnectionMessageCommandEvent(con, response);
            ctx.getEventBus().subscribe(ConnectionMessageCommandResultEvent.class, new ActionOnFirstEventListener(ctx,
                    new CommandResultFilter(responseEvent), new BaseEventBotAction(ctx) {
                        @Override
                        protected void doRun(Event event, EventListener executingListener) throws Exception {
                            ConnectionMessageCommandResultEvent resultEvent = (ConnectionMessageCommandResultEvent) event;
                            if (resultEvent.isSuccess()) {
                                Model agreementMessage = MessageUtils.processingMessage(
                                        "Verify the generated payment and accept it before distributing the link.");
                                MessageUtils.addProposes(agreementMessage,
                                        ((ConnectionMessageCommandSuccessEvent) resultEvent).getWonMessage()
                                                .getMessageURI());
                                ctx.getEventBus().publish(new ConnectionMessageCommandEvent(con, agreementMessage));
                            } else {
                                logger.error("FAILURE RESPONSE EVENT FOR PROPOSAL PAYLOAD");
                            }
                        }
                    }));
            ctx.getEventBus().publish(responseEvent);
        } catch (Exception e) {
            // FIXME: catch should only handle a specific exception type
            logger.warn("Paypal payment could not be generated.", e);
            ctx.getEventBus().publish(
                    PaypalBot.makeProcessingMessage("PayPal payment could not be generated: " + e.getMessage(), con));
            payCtx.setStatus(PaymentStatus.FAILURE);
            botCtx.setPaymentContext(con.getAtomURI(), payCtx);
        }
    }
}
