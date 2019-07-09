// clock with Ableton Link synchronization

LinkClock : TempoClock {
	var <syncMeter = false, <id,
	meterChangeResp, meterQueryResp;

	*newFromTempoClock { |clock|
		^super.new(
			clock.tempo,
			clock.beats,
			clock.seconds,
			clock.queue.maxSize
		).prInitFromTempoClock(clock)
	}

	init { |tempo, beats, seconds, queueSize|
		super.init(tempo, beats, seconds, queueSize);
		id = 0x7FFFFFFF.rand;  // to ignore 'self' queries
		meterQueryResp = OSCFunc({ |msg|
			NetAddr.broadcastFlag = true;
			// maybe some peers had to take a different port
			(57120 .. 57127).do { |port|
				NetAddr("255.255.255.255", port).sendMsg(
					'/LinkClock/meterReply', id, msg[1], syncMeter.asInteger,
					this.beats, this.beatsPerBar, this.baseBarBeat, this.beatInBar
				);
			};
		}, '/LinkClock/queryMeter');
	}

	syncMeter_ { |bool|
		syncMeter = bool;
		if(syncMeter) {
			if(this.numPeers == 0) {
				// maybe just started the clock; allow a beat for Link to hook up
				// you would think it should go onto 'this'
				// but the relationship between beats and seconds is unstable initially
				// in practice it works much better to schedule the sync on a stable clock
				SystemClock.sched(1.0, {
					// this.numPeers: might have changed in the last 2 seconds
					this.resyncMeter(verbose: this.numPeers > 0)
				});
			} {
				this.resyncMeter;
			};
			meterChangeResp = OSCFunc({ |msg|
				if(msg[1] != id) {  // ignore message that I sent
					// [2] = beatsPerBar, [3] = remote clock's real time, [4] = remote clock's barline
					// also, 5/8 means maybe setting the barline to a half-beat
					// but we have to round the barline because OSC receipt time is inexact
					// -- get the rounding factor from baseBarBeat.asFraction
					// *and* prSetMeterAtBeat because we do not want to broadcast here
					this.prSetMeterAtBeat(msg[2],
						(this.beats + msg[4] - msg[3]).round(msg[4].asFraction[1].reciprocal)
					);
				};
			}, '/LinkClock/changeMeter');
		} {
			meterChangeResp.free;
		};
	}

	setMeterAtBeat { |newBeatsPerBar, beats|
		if(syncMeter) {
			NetAddr.broadcastFlag = true;
			(57120..57127).do { |port|
				NetAddr("255.255.255.255", port).sendMsg(
					'/LinkClock/changeMeter', id, newBeatsPerBar, this.beats, beats
				);
			};
		};
		this.prSetMeterAtBeat(newBeatsPerBar, beats);
	}

	queryMeter { |action, timeout = 0.2|
		var replies = IdentityDictionary.new,
		resp;
		if(this.numPeers > 0) {
			resp = OSCFunc({ |msg, time, addr|
				if(msg[1] != id) {
					replies.put(msg[1], (
						id: msg[1],
						queriedAtBeat: msg[2],
						syncMeter: msg[3].asBoolean,
						beats: msg[4],
						beatsPerBar: msg[5],
						baseBarBeat: msg[6],
						beatInBar: msg[7]
					));
				};
			}, '/LinkClock/meterReply');
			{
				NetAddr.broadcastFlag = true;
				// maybe some peers had to take a different port
				(57120 .. 57127).do { |port|
					NetAddr("255.255.255.255", port).sendMsg('/LinkClock/queryMeter', this.beats);
				};
				(timeout * this.tempo).wait;  // seconds, not beats
				resp.free;
				action.value(replies.values);
			}.fork(this);
		} {
			// no peers, don't bother asking
			// sched is needed to make sure Conditions waiting for this method still work
			this.sched(0, { action.value(List.new) });
		};
	}

	adjustMeterBase { |localBeats, remoteBeats, round = 1|
		baseBarBeat = baseBarBeat + ((localBeats - remoteBeats) % beatsPerBar).round(round);
	}

	// if there are peers with syncMeter = true, align my clock to their common barline
	// ignore peers with syncMeter = false
	resyncMeter { |round, verbose = true|
		var replies, cond = Condition.new, bpbs, baseBeats, denom;
		var newBeatsPerBar, newBase;
		fork {
			this.queryMeter { |val|
				replies = val;
				cond.unhang;
			};
			cond.hang;
			replies = replies.select { |reply| reply[\syncMeter] };
			if(replies.size > 0) {
				// verify beatsPerBar, and myBeats - beatInBar, are common across peers
				// (if the network is glitchy, we may have replies from different trials.
				// but the difference between query time and remote beatInBar should be
				// consistent.)
				// also calculate baseBarBeat common denominator
				bpbs = Set.new;
				baseBeats = Set.new;
				denom = 1;
				replies.do { |reply, i|
					denom = denom lcm: reply[\baseBarBeat].asFraction[1];
					bpbs.add(reply[\beatsPerBar]);
					baseBeats.add(reply[\queriedAtBeat] - reply[\beatInBar]);
				};
				if(round.isNil) { round = denom.reciprocal };
				// Now it gets tricky. We need to reduce baseBeats based on beatsPerBar.
				// All 'syncMeter' peers should have the same beatsPerBar (size == 1).
				// But, if something failed, maybe there are more.
				// So we have to do a two-step check.
				if(bpbs.size == 1) {
					// 'choose' = easy way to get one item from an unordered collection
					newBeatsPerBar = bpbs.choose;
					// Sets, by definition, cannot hold multiple items of equivalent value
					// so this automatically collapses to minimum size!
					baseBeats = baseBeats.collect { |x| x.round(round) % newBeatsPerBar };
					if(baseBeats.size == 1) {
						// do not change beats! do not ever change beats here!
						// calculate baseBarBeat such that my local beatInBar will match theirs
						// myBeats = replies.choose[\queriedAtBeat].round(round);
						// theirPhase = bibs.choose;  // should be only one
						newBase = baseBeats.choose;
						if(verbose) { "syncing meter to %, base = %\n".postf(bpbs.choose, newBase) };
						this.prSetMeterAtBeat(bpbs.choose, newBase);  // local only
					} {
						// this should not happen
						Error("Discrepancy among 'syncMeter' Link peers; cannot sync barlines").throw;
					};
				} {
					Error("Discrepancy among 'syncMeter' Link peers; cannot sync barlines").throw;
				};
			} {
				if(verbose) {
					"Found no SC Link peers with syncMeter set to true; cannot resync".warn;
				};
			}
		}
	}

	stop {
		meterQueryResp.free;
		meterChangeResp.free;
		super.stop;
	}

	numPeers {
		_LinkClock_NumPeers
		^this.primitiveFailed
	}

	//override TempoClock primitives
	beats_ { |beats|
		_LinkClock_SetBeats
		^this.primitiveFailed
	}

	setTempoAtBeat { |newTempo, beats|
		_LinkClock_SetTempoAtBeat
		^this.primitiveFailed
	}

	setTempoAtSec { |newTempo, secs|
		_LinkClock_SetTempoAtTime
		^this.primitiveFailed
	}

	latency {
		_LinkClock_GetLatency
		^this.primitiveFailed
	}

	latency_ { |lat|
		_LinkClock_SetLatency
		^this.primitiveFailed
	}

	quantum {
		_LinkClock_GetQuantum;
		^this.primitiveFailed
	}

	quantum_ { |quantum|
		_LinkClock_SetQuantum;
		^this.primitiveFailed
	}

	// PRIVATE
	prStart { |tempo, beats, seconds|
		_LinkClock_New
		^this.primitiveFailed
	}

	prSetMeterAtBeat { |newBeatsPerBar, beats|
		super.setMeterAtBeat(newBeatsPerBar, beats);
	}

	// run tempo changed callback
	prTempoChanged { |tempo, beats, secs, clock|
		this.changed(\tempo);
	}

	prStartStopSync { |isPlaying|
		this.changed(if(isPlaying, \linkStart, \linkStop));
	}

	prNumPeersChanged { |numPeers|
		this.changed(\numPeers, numPeers);
	}

	prInitFromTempoClock { |clock|
		var oldQueue;
		//stop TempoClock and save its queue
		clock.stop;
		oldQueue = clock.queue.copy;
		this.setMeterAtBeat(clock.beatsPerBar, clock.baseBarBeat);

		// queue format is grouped into threes:
		// [size, time0, task0, priority0, time1, task1, priority1, ...]
		// below, then, oldQueue[i] == time(j)
		// and oldQueue[i + 1] = task(j) -- schedAbs copies into the new queue
		forBy(1, oldQueue.size-1, 3) { |i|
			var task = oldQueue[i + 1];
			//reschedule task with this clock
			this.schedAbs(oldQueue[i], task);
		};

		^this
	}
}
