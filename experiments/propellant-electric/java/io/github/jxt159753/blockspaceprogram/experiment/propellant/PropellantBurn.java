package io.github.jxt159753.blockspaceprogram.experiment.propellant;

/** Full-power test with aligned engines and one fuel group. All quantities are in SI units. */
public record PropellantBurn(Engine chemical, Engine electric, double electricPower, boolean electricUsesFuel) {
    private static final double STANDARD_GRAVITY = 9.80665;

    public Step advance(Supply supply, double availableSeconds) {
        double fuel = supply.fuel();
        double energy = supply.energy();
        double elapsed = 0;
        double electricSeconds = 0;
        double impulse = 0;
        double deltaV = 0;
        while (availableSeconds > 0) {
            boolean chemicalOn = chemical.thrust() > 0 && fuel > 0;
            boolean electricOn = electric.thrust() > 0 && energy > 0 && (fuel > 0 || !electricUsesFuel);
            if (!chemicalOn && !electricOn) {
                break;
            }

            double thrust = (chemicalOn ? chemical.thrust() : 0) + (electricOn ? electric.thrust() : 0);
            double flow = (chemicalOn ? chemical.fuelFlow() : 0)
                    + (electricOn && electricUsesFuel ? electric.fuelFlow() : 0);
            double fuelLimit = flow > 0 ? fuel / flow : Double.POSITIVE_INFINITY;
            double electricLimit = electricOn ? energy / electricPower : Double.POSITIVE_INFINITY;
            double seconds = Math.min(availableSeconds, Math.min(fuelLimit, electricLimit));
            double consumed = Math.min(fuel, flow * seconds);
            double mass = supply.dryMass() + fuel;
            impulse += thrust * seconds;
            deltaV += flow > 0 ? -thrust / flow * Math.log1p(-consumed / mass) : thrust * seconds / mass;
            fuel = seconds == fuelLimit ? 0 : fuel - consumed;
            if (electricOn) {
                energy = Math.max(0, seconds == electricLimit ? 0 : energy - electricPower * seconds);
                electricSeconds += seconds;
            }
            elapsed += seconds;
            availableSeconds -= seconds;
        }
        return new Step(new Supply(supply.dryMass(), fuel, energy), elapsed, electricSeconds, deltaV, impulse);
    }

    public record Engine(double thrust, double specificImpulse) {
        public static final Engine OFF = new Engine(0, 0);

        double fuelFlow() {
            return thrust / (STANDARD_GRAVITY * specificImpulse);
        }
    }

    public record Supply(double dryMass, double fuel, double energy) {}

    public record Step(Supply remaining, double seconds, double electricSeconds, double deltaV, double impulse) {}
}
