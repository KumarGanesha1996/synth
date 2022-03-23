import sources.Gated;
import sources.SignalSource;
import sources.utils.SourceValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

import static utils.FrequencyManipulations.getFrequencyBySemitones;

public class MyMonoSynth implements Synth{
    SignalSource voice;
    SourceValue pitch;
    Gated gate;
    List<Integer> notes;

    public MyMonoSynth(SignalSource voice, SourceValue pitch, Gated gate){
        this.voice = voice;
        this.pitch = pitch;
        this.gate = gate;
        notes = new ArrayList<>();
    }

    @Override
    public void noteOn(int note, double velocity) {
        notes.add(note);
        pitch.setValue(SignalSource.frequencyToVoltage(getFrequencyBySemitones(note)));
        gate.gateOn();
    }

    @Override
    public void noteOff(int note, double velocity) {
        notes.removeAll(List.of(new Integer[]{note}));
        List<Integer> newNotes = new ArrayList<>();
        for(Integer el : notes)
            if(el != note)
                newNotes.add(el);
        notes = newNotes;
        if(notes.isEmpty())
            gate.gateOff();
        else{
            int newNote = notes.get(notes.size()-1);
            pitch.setValue(SignalSource.frequencyToVoltage(getFrequencyBySemitones(newNote)));
            gate.gateOn();
        }
    }


    @Override
    public double getSample(int sampleId) {
        return voice.getSample(sampleId);
    }
}
