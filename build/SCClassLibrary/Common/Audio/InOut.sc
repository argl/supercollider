
ControlName
{
	var <>name, <>index, <>rate, <>defaultValue;
	
	*new { arg name, index, rate, defaultValue;
		^super.newCopyArgs(name, index, rate, defaultValue)
	}

	printOn { arg stream;
		stream << "   P " << index.asString;
		if (name.notNil) { stream << " " << name; };
		if (rate.notNil) { stream << " " << rate; };
		if (defaultValue.notNil) { stream << " " << defaultValue; };
		stream << "\n"
	}	
		
}

Control : MultiOutUGen {
	var <values;
	
	*names { arg names;
		var synthDef, index;
		synthDef = UGen.buildSynthDef;
		index = synthDef.controls.size;
		names.asArray.do({ arg name, i; 
			synthDef.controlNames = synthDef.controlNames.add(
				ControlName(name.asString, index + i)
			);
		});
	}
	*kr { arg values;
		^this.multiNewList(['control'] ++ values.asArray)
	}
	*ir { arg values;
		^this.multiNewList(['scalar'] ++ values.asArray)
	}
	init { arg ... argValues;
		values = argValues;
		if (synthDef.notNil, { 
			specialIndex = synthDef.controls.size;
			synthDef.controls = synthDef.controls.addAll(values);
		});
		^this.initOutputs(values.size, rate)
	}
	*isControlUGen { ^true }
}

TrigControl : Control {}

LagControl : Control {	
	*kr { arg values, lags, outputs;
		values = values.asArray;
		lags = lags.asArray;
		if (values.size != lags.size, {
			"LagControl values.size != lags.size".error; 
			^nil 
		});
		values = values.clump(16);
		lags = lags.clump(16);
		outputs = [];
		values.size.do({ arg i;
			outputs = outputs ++ this.multiNewList(['control'] ++ values.at(i) ++ lags.at(i));
		});
		^outputs
	}
	*ir {
		^this.shouldNotImplement(thisMethod)
	}
	init { arg ... stuff;
		var lags, size, size2;
		size = stuff.size;
		size2 = size >> 1;
		values = stuff.copyRange(0, size2-1);
		inputs = stuff.copyRange(size2, size-1);
		if (synthDef.notNil, { 
			specialIndex = synthDef.controls.size;
			synthDef.controls = synthDef.controls.addAll(values);
		});
		^this.initOutputs(values.size, rate)
	}
}

AbstractIn : MultiOutUGen {
 	*isInputUGen { ^true }
}

In : AbstractIn {	
	*ar { arg bus = 0, numChannels = 1;
		^this.multiNew('audio', numChannels, bus)
	}
	*kr { arg bus = 0, numChannels = 1;
		^this.multiNew('control', numChannels, bus)
	}
	init { arg numChannels ... argBus;
		inputs = argBus.asArray;
		^this.initOutputs(numChannels, rate)
	}
}

LagIn : AbstractIn {	
	*kr { arg bus = 0, numChannels = 1, lag = 0.1;
		^this.multiNew('control', numChannels, bus, lag)
	}
	init { arg numChannels ... argInputs;
		inputs = argInputs.asArray;
		^this.initOutputs(numChannels, rate)
	}
}

InFeedback : AbstractIn {	
	*ar { arg bus = 0, numChannels = 1;
		^this.multiNew('audio', numChannels, bus)
	}
	init { arg numChannels ... argBus;
		inputs = argBus.asArray;
		^this.initOutputs(numChannels, rate)
	}
}

InTrig : AbstractIn {	
	*kr { arg bus = 0, numChannels = 1;
		^this.multiNew('control', numChannels, bus)
	}
	init { arg numChannels ... argBus;
		inputs = argBus.asArray;
		^this.initOutputs(numChannels, rate)
	}
}

AbstractOut : UGen {
	numOutputs { ^0 }
	writeOutputSpecs {}
 	checkInputs {
 		if (rate == 'audio', {
 			for(1, inputs.size - 1, { arg i;
 				if (inputs.at(i).rate != 'audio', { 
 					^(" input at index " + i + 
 						"(" + inputs.at(i) + ") is not audio rate");
 				});
 			});
 		});
 		^nil
 	}
 	
 	*isOutputUGen { ^true }
 	*replaceZeroesWithSilence { arg array;
 		// this replaces zeroes with audio rate silence.
 		// sub collections are deep replaced
 		var numZeroes, silentChannels, pos = 0;
 		
 		numZeroes = array.count({ arg item; item == 0.0 });
 		if (numZeroes == 0, { ^array });
 		
 		silentChannels = Silent.ar(numZeroes).asCollection;
 		array.do({ arg item, i;
 			var res; 
 			if (item == 0.0, { 
 				array.put(i, silentChannels.at(pos)); 
 				pos = pos + 1;
 			}, { 
 				if(item.isSequenceableCollection, {
 					res = this.replaceZeroesWithSilence(item);
 					array.put(i, res);
 				});
 			});
 		});
 		^array;
 	}
}

Out : AbstractOut {
	*ar { arg bus, channelsArray;
		channelsArray = this.replaceZeroesWithSilence(channelsArray.asArray);
		this.multiNewList(['audio', bus] ++ channelsArray)
		^0.0		// Out has no output
	}
	*kr { arg bus, channelsArray;
		this.multiNewList(['control', bus] ++ channelsArray.asArray)
		^0.0		// Out has no output
	}
	*numFixedArgs { ^1 }
}

ReplaceOut : Out {}
OffsetOut : Out {}

XOut : AbstractOut {
	*ar { arg bus, xfade, channelsArray;
		channelsArray = this.replaceZeroesWithSilence(channelsArray.asArray);
		this.multiNewList(['audio', bus, xfade] ++ channelsArray)
		^0.0		// Out has no output
	}
	*kr { arg bus, xfade, channelsArray;
		this.multiNewList(['control', bus, xfade] ++ channelsArray.asArray)
		^0.0		// Out has no output
	}
	*numFixedArgs { ^2 }
	checkInputs {
 		if (rate == 'audio', {
 			for(2, inputs.size - 1, { arg i;
 				if (inputs.at(i).rate != 'audio', { 
 					^(" input at index " + i + 
 						"(" + inputs.at(i) + ") is not audio rate");
 				});
 			});
 		});
 		^nil
 	}
}


SharedOut : AbstractOut {
	*kr { arg bus, channelsArray;
		this.multiNewList(['control', bus] ++ channelsArray.asArray)
		^0.0		// Out has no output
	}
}

SharedIn : AbstractIn {	
	*kr { arg bus = 0, numChannels = 1;
		^this.multiNew('control', numChannels, bus)
	}
	init { arg numChannels ... argBus;
		inputs = argBus.asArray;
		^this.initOutputs(numChannels, rate)
	}
}


