package won.payment.paypal.bot.event.proposal;

import java.net.URI;

import won.bot.framework.eventbot.event.BaseAtomAndConnectionSpecificEvent;
import won.protocol.model.Connection;

// TODO: why is this a separate class? this case should be handled by won
/**
 * When the user retracts a message.
 * 
 * @author schokobaer
 */
public class ProposalRejectedEvent extends BaseAtomAndConnectionSpecificEvent {
    private URI proposalUri;

    public ProposalRejectedEvent(Connection con, URI proposalUri) {
        super(con);
        this.proposalUri = proposalUri;
    }

    public URI getProposalUri() {
        return proposalUri;
    }
}
