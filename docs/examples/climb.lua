local flight = assert(peripheral.find("bsp_flight_terminal"), "Connect a flight terminal first")
local targetAltitude = 1000
local initial = flight.getTelemetry()
if initial.altitude >= targetAltitude then
    print("Already at target altitude")
    return
end
assert(initial.availableThrust > initial.mass * initial.gravity, "The active stage needs more thrust to lift off")

flight.holdAttitude(0, 0, 0)
local ok, reason = pcall(function()
    flight.setVerticalSpeed(20)
    while true do
        sleep(0.1)
        local data = flight.getTelemetry()
        if data.altitude >= targetAltitude then
            print(("Reached %.1f m at %.1f m/s"):format(data.altitude, data.verticalSpeed))
            return
        end
        assert(data.throttleMode == "vertical_speed", "Automatic throttle was cancelled")
        assert(data.availableThrust > 0, "The active stage has lost thrust")
    end
end)

flight.releaseControl()
if not ok then
    error(reason, 0)
end
