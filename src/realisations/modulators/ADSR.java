package realisations.modulators;

import sources.AbstractSignalSource;
import sources.SignalSource;
import sources.modulators.Envelope;
import sources.utils.DC;
import sources.utils.Socket;

import javax.naming.ldap.SortKey;

import static java.lang.Math.max;

public class ADSR extends AbstractSignalSource implements Envelope {
    private final Socket attack, decay, sustain, release;
    private double currentSample;
    //private boolean legato, retrigger;
    private boolean gate, attackStage;


    public ADSR(double attack, double decay, double sustain, double release) {
        this(new DC(attack), new DC(decay), new DC(sustain), new DC(release));
    }
    public ADSR(SignalSource attackSource, SignalSource decaySource, SignalSource sustainSource, SignalSource releaseSource) {
        attack = new Socket(attackSource);
        decay = new Socket(decaySource);
        sustain = new Socket(sustainSource);
        release = new Socket(releaseSource);
        currentSample = 0;
        gate = false;
        attackStage = false;
    }

    public Socket attack(){
        return attack;
    }
    public Socket decay(){
        return decay;
    }
    public Socket sustain(){
        return sustain;
    }
    public Socket release(){
        return release;
    }

    public double getSample(int sampleId) {
        if (checkAndUpdateSampleId(sampleId)) {
            if (attackStage) {
                currentSample += 1 / attack().getTime(sampleId) / sampleRate;
                if (currentSample >= 1) {
                    currentSample = 1;
                    attackStage = false;
                }
            }
            else if (!gate)
                currentSample = max(currentSample - 1 / release().getTime(sampleId) / sampleRate, 0);
            else currentSample = max(currentSample - 1 / decay().getTime(sampleId) / sampleRate, sustain().getSample(sampleId));
        }
        return currentSample;
    }

    @Override
    public void gateOn() {
        attackStage = true;
        gate = true;
    }

    public void gateOff() {
        attackStage = false;
        gate = false;
    }
}