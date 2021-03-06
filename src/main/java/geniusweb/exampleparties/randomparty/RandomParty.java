package geniusweb.exampleparties.randomparty;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Collectors;

import geniusweb.actions.Accept;
import geniusweb.actions.Action;
import geniusweb.actions.LearningDone;
import geniusweb.actions.Offer;
import geniusweb.actions.PartyId;
import geniusweb.actions.Vote;
import geniusweb.actions.VoteWithValue;
import geniusweb.actions.Votes;
import geniusweb.actions.VotesWithValue;
import geniusweb.bidspace.AllPartialBidsList;
import geniusweb.inform.ActionDone;
import geniusweb.inform.Finished;
import geniusweb.inform.Inform;
import geniusweb.inform.OptIn;
import geniusweb.inform.OptInWithValue;
import geniusweb.inform.Settings;
import geniusweb.inform.Voting;
import geniusweb.inform.YourTurn;
import geniusweb.issuevalue.Bid;
import geniusweb.party.Capabilities;
import geniusweb.party.DefaultParty;
import geniusweb.profile.PartialOrdering;
import geniusweb.profile.Profile;
import geniusweb.profile.utilityspace.UtilitySpace;
import geniusweb.profileconnection.ProfileConnectionFactory;
import geniusweb.profileconnection.ProfileInterface;
import geniusweb.progress.Progress;
import geniusweb.progress.ProgressRounds;
import tudelft.utilities.logging.Reporter;
import geniusweb.bidspace.BidsWithUtility;
import geniusweb.profile.utilityspace.LinearAdditive;
import geniusweb.bidspace.Interval;
import java.math.BigDecimal;

/**
 * A party that places random bids and accepts when it receives an offer with
 * sufficient utility. This party is also a demo on how to support the various
 * protocols, which causes this party to look a bit complex.
 * 
 * <h2>parameters</h2>
 * <table >
 * <caption>parameters</caption>
 * <tr>
 * <td>minPower</td>
 * <td>This value is used as minPower for placed {@link Vote}s. Default value is
 * 2.</td>
 * </tr>
 * <tr>
 * <td>maxPower</td>
 * <td>This value is used as maxPower for placed {@link Vote}s. Default value is
 * infinity.</td>
 * </tr>
 * </table>
 */
public class RandomParty extends DefaultParty {

	private Bid lastReceivedBid = null;
	private PartyId me;
	private final Random random = new Random();
	protected ProfileInterface profileint = null;
	private Progress progress;
	private Settings settings;
	private Votes lastvotes;
	private VotesWithValue lastvoteswithvalue;
	private String protocol;
	private BidsWithUtility bidutils;
	private Double maxBidUtil = 0.0;

	public RandomParty() {
	}

	public RandomParty(Reporter reporter) {
		super(reporter); // for debugging
	}
	
	public RandomParty(LinearAdditive space) {
		// this.utilspace = space;
		bidutils = new BidsWithUtility(space);
	}

	@Override
	public void notifyChange(Inform info) {
		try {
			if (info instanceof Settings) {
				Settings settings = (Settings) info;
				this.me = settings.getID();
				this.progress = settings.getProgress();
				this.settings = settings;
				this.protocol = settings.getProtocol().getURI().getPath();
				if ("Learn".equals(protocol)) {
					getConnection().send(new LearningDone(me));
				} else {
					this.profileint = ProfileConnectionFactory.create(
							settings.getProfile().getURI(), getReporter());
				}
			} else if (info instanceof ActionDone) {
				Action otheract = ((ActionDone) info).getAction();
				if (otheract instanceof Offer) {
					lastReceivedBid = ((Offer) otheract).getBid();
				}
			} else if (info instanceof YourTurn) {
				makeOffer();
			} else if (info instanceof Finished) {
				getReporter().log(Level.INFO, "Final ourcome:" + info);
				terminate(); // stop this party and free resources.
			} else if (info instanceof Voting) {
				switch (protocol) {
				case "MOPAC":
					lastvotes = vote((Voting) info);
					getConnection().send(lastvotes);
					break;
				case "MOPAC2":
					lastvoteswithvalue = voteWithValue((Voting) info);
					getConnection().send(lastvoteswithvalue);
				}
			} else if (info instanceof OptIn) {
				// just repeat our last vote.
				getConnection().send(lastvotes);
			} else if (info instanceof OptInWithValue) {
				getConnection().send(lastvoteswithvalue);
			}
		} catch (Exception e) {
			throw new RuntimeException("Failed to handle info", e);
		}
		updateRound(info);
		// try {
		// 	if (info instanceof Settings) {
		// 		settings = (Settings) info;
		// 		this.me = settings.getID();
		// 		this.progress = settings.getProgress();
		// 		Object newe = settings.getParameters().get("e");
		// 		String protocol = settings.getProtocol().getURI().getPath();
		// 		if ("Learn".equals(protocol)) {
		// 			getConnection().send(new LearningDone(me));
		// 		} else {
		// 			this.profileint = ProfileConnectionFactory.create(
		// 					settings.getProfile().getURI(), getReporter());
		// 		}

		// 	} else if (info instanceof ActionDone) {
		// 		Action otheract = ((ActionDone) info).getAction();
		// 		if (otheract instanceof Offer) {
		// 			lastReceivedBid = ((Offer) otheract).getBid();
		// 		}
		// 	} else if (info instanceof YourTurn) {
		// 		makeOffer();
		// 	} else if (info instanceof Finished) {
		// 		getReporter().log(Level.INFO, "Final ourcome:" + info);
		// 		terminate(); // stop this party and free resources.
		// 	} else if (info instanceof Voting) {
		// 		lastvotes = vote((Voting) info);
		// 		getConnection().send(lastvotes);
		// 	} else if (info instanceof OptIn) {
		// 		getConnection().send(lastvotes);
		// 	}
		// } catch (Exception ex) {
		// 	getReporter().log(Level.SEVERE, "Failed to handle info", ex);
		// }
		// updateRound(info);
	}

	@Override
	public Capabilities getCapabilities() {
		return new Capabilities(
				new HashSet<>(Arrays.asList("SAOP")),
				Collections.singleton(Profile.class));
	}

	@Override
	public String getDescription() {
		return "places random bids until it can accept an offer with utility >0.7. "
				+ "Parameters minPower and maxPower can be used to control voting behaviour.";
	}

	@Override
	public void terminate() {
		super.terminate();
		if (this.profileint != null) {
			this.profileint.close();
			this.profileint = null;
		}
	}

	/******************* private support funcs ************************/

	/**
	 * Update {@link #progress}
	 * 
	 * @param info the received info. Used to determine if this is the last info
	 *             of the round
	 */
	private void updateRound(Inform info) {
		if (protocol == null)
			return;
		switch (protocol) {
		case "SAOP":
		case "SHAOP":
			if (!(info instanceof YourTurn))
				return;
			break;
		case "MOPAC":
			if (!(info instanceof OptIn))
				return;
			break;
		case "MOPAC2":
			if (!(info instanceof OptInWithValue))
				return;
			break;
		default:
			return;
		}
		// if we get here, round must be increased.
		if (progress instanceof ProgressRounds) {
			progress = ((ProgressRounds) progress).advance();
		}

	}

	/**
	 * send our next offer
	 */
	private void makeOffer() throws IOException {
		Action action;
		AllPartialBidsList bidspace = new AllPartialBidsList(
					profileint.getProfile().getDomain());

		// Interval range = bidutils.getRange();
		// BigDecimal maxBidUtil = range.getMax();
		// BigDecimal stepVal = maxBidUtil.divide(new BigDecimal(10.));
		// // for demo. Obviously full bids have higher util in general
		// 
		// Bid bid = bidutils.getBids(new Interval(maxBidUtil.subtract(stepVal),maxBidUtil)).get(0);
		this.maxBidUtil -= 0.05;
		Bid bid = null;
		Bid checkBid = null;
		for (int attempt = 0; attempt < 20; attempt++) {
			long i = random.nextInt(bidspace.size().intValue());
			checkBid = bidspace.get(BigInteger.valueOf(i));
			Double checkUtil = bidUtil(checkBid);
			if (checkUtil > this.maxBidUtil) {
				this.maxBidUtil = checkUtil;
				bid = checkBid;
			}
			if (isGood(bid)) {
				break;
			}
		}
		if (isGood(lastReceivedBid) && bidUtil(lastReceivedBid) > this.maxBidUtil) {
			action = new Accept(me, lastReceivedBid);
		} else {
			action = new Offer(me, bid);
		}
		getConnection().send(action);

	}

	/**
	 * @param bid the bid to check
	 * @return true iff bid is good for us.
	 */
	private boolean isGood(Bid bid) {
		if (bid == null)
			return false;
		Profile profile;
		try {
			profile = profileint.getProfile();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		if (profile instanceof UtilitySpace) {
			return ((UtilitySpace) profile).getUtility(bid).doubleValue() > 0.6;
		}
		if (profile instanceof PartialOrdering) {
			return ((PartialOrdering) profile).isPreferredOrEqual(bid,
					profile.getReservationBid());
		}
		return false;
	}

	private Double bidUtil(Bid bid) {
			Profile profile;
			try {
				profile = profileint.getProfile();
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
			return ((UtilitySpace) profile).getUtility(bid).doubleValue();
	}


	/**
	 * @param voting the {@link Voting} object containing the options
	 * 
	 * @return our next Votes.
	 */
	private Votes vote(Voting voting) throws IOException {
		Object val = settings.getParameters().get("minPower");
		Integer minpower = (val instanceof Integer) ? (Integer) val : 2;
		val = settings.getParameters().get("maxPower");
		Integer maxpower = (val instanceof Integer) ? (Integer) val
				: Integer.MAX_VALUE;

		Set<Vote> votes = voting.getBids().stream().distinct()
				.filter(offer -> isGood(offer.getBid()))
				.map(offer -> new Vote(me, offer.getBid(), minpower, maxpower))
				.collect(Collectors.toSet());
		return new Votes(me, votes);
	}

	/**
	 * @param voting the {@link Voting} object containing the options
	 * 
	 * @return our next Votes. Returns only votes on good bids and tries to
	 *         distribute vote values evenly over all good bids.
	 */
	private VotesWithValue voteWithValue(Voting voting) throws IOException {
		Object val = settings.getParameters().get("minPower");
		Integer minpower = (val instanceof Integer) ? (Integer) val : 2;
		val = settings.getParameters().get("maxPower");
		Integer maxpower = (val instanceof Integer) ? (Integer) val
				: Integer.MAX_VALUE;

		List<Bid> goodbids = voting.getBids().stream().distinct()
				.filter(offer -> isGood(offer.getBid()))
				.map(offer -> offer.getBid()).collect(Collectors.toList());

		if (goodbids.isEmpty()) {
			return new VotesWithValue(me, Collections.emptySet());
		}
		// extra difficulty now is to have the utility sum to exactly 100
		int mostvalues = 100 / goodbids.size();
		int value = 100 - mostvalues * (goodbids.size() - 1);
		Set<VoteWithValue> votes = new HashSet<>();
		for (Bid bid : goodbids) {
			votes.add(new VoteWithValue(me, bid, minpower, maxpower, value));
			value = mostvalues;
		}
		return new VotesWithValue(me, votes);
	}

}
