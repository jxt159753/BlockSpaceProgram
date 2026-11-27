local flight = assert(peripheral.find("bsp_flight_terminal"), "Connect a flight terminal first")
local data = flight.getTelemetry()
local orbit = assert(data.orbit, "Put the bound ship in orbit first")
assert(orbit.onRails and orbit.soi == "minecraft:overworld" and orbit.apoapsis,
    "Use an uncrewed ship in a bound orbit around the mother planet")
assert(not orbit.maneuver, "The ship already has a maneuver node")

local beforeApoapsis = orbit.apoapsis
local beforeFuel = data.fuel
local seconds = orbit.seconds + 5
flight.addManeuverNode(seconds, 30, 0, 0)
flight.executeManeuverNode()

repeat
    sleep(0.1)
    data = flight.getTelemetry()
    orbit = assert(data.orbit, "The ship left orbit")
until orbit.seconds >= seconds and not orbit.maneuver

assert(data.fuel < beforeFuel, "The maneuver did not consume fuel")
print(("Apoapsis: %.1f m -> %.1f m"):format(beforeApoapsis, orbit.apoapsis))
print(("Fuel: %.2f kg -> %.2f kg"):format(beforeFuel, data.fuel))
