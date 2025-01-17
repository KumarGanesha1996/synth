package synthesizer.realisations.oscillators;

import synthesizer.sources.SignalSource;
import synthesizer.sources.oscillators.AbstractPMOscillator;

import static java.lang.Math.PI;
import static java.lang.Math.sin;

public class PMSineOscillator extends AbstractPMOscillator {
    public PMSineOscillator() {
    }

    public PMSineOscillator(double frequency) {
        super(frequency);
    }

    public PMSineOscillator(SignalSource frequencySource) {
        super(frequencySource);
    }

    public PMSineOscillator(SignalSource frequencySource, SignalSource phaseSource) {
        super(frequencySource, phaseSource);
    }

    @Override
    public double getAmplitude(int sampleId) {
        return sin(getPtr(sampleId) * 2 * PI);
    }
}
