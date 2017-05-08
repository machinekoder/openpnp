/*
 * Copyright (C) 2011 Jason von Nieda <jason@vonnieda.org>
 * 
 * This file is part of OpenPnP.
 * 
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * OpenPnP is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with OpenPnP. If not, see
 * <http://www.gnu.org/licenses/>.
 * 
 * For more information about OpenPnP visit http://openpnp.org
 */

package org.openpnp.machine.reference.driver;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import javax.swing.Action;

import org.openpnp.gui.support.PropertySheetWizardAdapter;
import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.reference.ReferenceActuator;
import org.openpnp.machine.reference.ReferenceHead;
import org.openpnp.machine.reference.ReferenceHeadMountable;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.driver.wizards.AbstractSerialPortDriverConfigurationWizard;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.PropertySheetHolder;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * TODO: Consider adding some type of heartbeat to the firmware. TODO: The whole movement wait lock
 * thing has to go. See if we can do a P4 type command like the other drivers to wait for movement
 * to complete. Disabled axes don't send status reports, so movement wait lock never happens.
 * Probably short moves also won't.
 */
public class DanielPnpDriver extends AbstractSerialPortDriver implements Runnable {

    @Attribute(required = false)
    private double feedRateMmPerMinute;
    @Element(required = false)
    private Location homeLocation = new Location(LengthUnit.Millimeters);

    private int placeBlowTime = 50;
    
    private static int X_AXIS = 2;
    private static int Y_AXIS = 1;
    private static int Z_AXIS = 3;
    private static int C_AXIS = 4;
    
    private double[] stepsPerMm;
    private double[] minimumLimits;
    private double[] maximumLimits;
    private double[] feedMmPerMinute;
    private double[] accelMmPerSecondSquared;
    private double cAxisCorrectionRadius = 0;//0.25;

    private double x, y, z, c;
    private double xCorrection, yCorrection;
    private Thread readerThread;
    private boolean disconnectRequested;
    private Object commandLock = new Object();
    private Object movementWaitLock = new Object();
    private String lastResponse;
    private boolean connected;
    
    private DigitalOutputs digitalOutputsBank1 = new DigitalOutputs(1);
    private DigitalOutputs digitalOutputsBank2 = new DigitalOutputs(2);

    public DanielPnpDriver() {
    	initializeStepsPerRevolution();
    	initializeLimits();
    	initializeFeedRate();
    	initializeAcceleration();
    }    
    
    private void initializeStepsPerRevolution() {
    	stepsPerMm = new double[5];
    	stepsPerMm[X_AXIS] = 160.0;
    	stepsPerMm[Y_AXIS] = -160.0;
    	stepsPerMm[Z_AXIS] = -80.0;
    	stepsPerMm[C_AXIS] = -160.0 / 18.0;
    }
    
    private void initializeLimits() {
    	minimumLimits = new double[5];
    	maximumLimits = new double[5];
    	minimumLimits[X_AXIS] = 0.0;
    	maximumLimits[X_AXIS] = 600.0;
    	minimumLimits[Y_AXIS] = -686.0;
    	maximumLimits[Y_AXIS] = 0.0;
    	minimumLimits[Z_AXIS] = -95.0;
    	maximumLimits[Z_AXIS] = 0.0;
    }
    
    private void initializeFeedRate() {
    	feedMmPerMinute = new double[5];
    	feedMmPerMinute[X_AXIS] = 20000.0;
    	feedMmPerMinute[Y_AXIS] = 20000.0;
    	feedMmPerMinute[Z_AXIS] = 8000.0;
    	feedMmPerMinute[C_AXIS] = 100000.0;
    }
    
    private void initializeAcceleration() {
    	accelMmPerSecondSquared = new double[5];
    	accelMmPerSecondSquared[X_AXIS] = 80000.0;
    	accelMmPerSecondSquared[Y_AXIS] = 80000.0;
    	accelMmPerSecondSquared[Z_AXIS] = 32000.0;
    	accelMmPerSecondSquared[C_AXIS] = 400000.0;
    }
    
    private boolean checkLimit(int axis, double pos) {
    	return ((pos >= minimumLimits[axis]) && (pos <= maximumLimits[axis]));
    }

    private class DriverStatus {
        public boolean ready = false;
        public boolean zeroPos = false;
        public boolean posError = false;
        
        public DriverStatus() { }
    }
    
    private class DigitalOutputs {
    	public boolean suck = false;
    	public boolean blow = false;
    	public boolean light = false;
    	public int bank = 0;
    	
    	public DigitalOutputs(int bank) {
    		this.bank = bank;
    	}
    	public int toBitmask() {
    		int bitmask = 0;
    		bitmask |= ((suck ? 1 : 0) << 16);
    		bitmask |= ((blow ? 1 : 0) << 17);
    		bitmask |= ((light ? 1 : 0) << 18);
    		return bitmask;
    	}
    }

    private DriverStatus getAxisStatus(int axis) throws Exception {
        String command = String.format("%1$d$", axis);
        String response = sendCommand(command);
        String data = response.split(Pattern.quote("$"))[1];
        
        int bitmask = Integer.parseInt(data);
        DriverStatus status = new DriverStatus();
        status.ready = ((bitmask & (1 << 0)) != 0);
        status.zeroPos = ((bitmask & (1 << 1)) != 0);
        status.posError = ((bitmask & (1 << 2)) != 0);

        return status;
    }
    
    private void initializeDigitalOutputs(DigitalOutputs outputs) throws Exception {
    	String command = String.format("%1$dL255", outputs.bank);
        String response = sendCommand(command);
        Logger.trace(response);
        if (!response.equals(command)) {
            throw new Exception("Initialisierung der Digitalen Outputs gescheitert");
        }
    }
    
    private void setDigitalOutputs(DigitalOutputs outputs) throws Exception {
    	String command = String.format("%1$dY%2$d", outputs.bank, outputs.toBitmask());
        String response = sendCommand(command);
        Logger.trace(response);
        if (!response.equals(command)) {
            throw new Exception("Digitalen Output setzen gescheitert");
        }
    }
	
	private void initializeAxis(int axis) throws Exception {
        // Positionsfehler zurücksetzen
		String command = String.format("%1$dD", axis);
        String response = sendCommand(command);
        Logger.trace(response);
        if (!response.equals(command)) {
            throw new Exception("Positionsfehler zurücksetzen gescheitert");
        }

        DriverStatus status = getAxisStatus(axis);
        if (!status.ready) {
            throw new Exception(String.format("Axis %1$d not ready", axis));
        }

        // Satz 11 aus EEPROM lesen
        command = String.format("%1$dy11", axis);
        response = sendCommand(command);
        if (!response.equals(command)) {
            throw new Exception("Laden der Daten aus dem EEPROM Fehlgeschlagen");
        }
		
		// Rampe einstellen
        command = String.format("%1$d:ramp_mode=0", axis);
		response = sendCommand(command);
		if (!response.equals(command)) {
			throw new Exception("Setzen der Rampe fehlgeschlagen");
		}
		
		// Motor starten
        startMotor(axis);
		
        // wait for axis to hit the home switch
		do {
			Thread.sleep(100);
			status = getAxisStatus(axis);
		} while (!(status.ready && status.zeroPos));
		
		setAcceleration(axis);
	}
	
	private void setAbsolutePositioning(int axis) throws Exception {
		String command = String.format("%1$dp2", axis);
		String response = sendCommand(command);
        if (!response.equals(command)) {
            throw new Exception("aktivieren von absoluter Positionierung gescheitert");
        }
	}
	
	private void startMotor(int axis) throws Exception {
		
		String command = String.format("%1$dA", axis);
		String response = sendCommand(command);
        if (!response.equals(command)) {
            throw new Exception("Motor starten gescheitert");
        }
	}
	
    @Override
    public synchronized void connect() throws Exception {
        super.connect();

        readerThread = new Thread(this);
        readerThread.start();

        boolean failed = false;
        for (int i = 1; i < 5 && !connected; i++) {
            try {
                String command = String.format("%1$dv", i);
                String response = sendCommand(command, 500);

                if (!response.contains(command)) {
                    failed = true;
                    break;
                }
            }
            catch (Exception e) {
                Logger.debug("Firmware version check failed", e);
                failed = true;
            }
        }

        connected = !failed;

        if (!connected) {
            throw new Exception("Unable to receive connection response");
        }

        Logger.debug("Version check succeded");

        // start init sequence
        // lese schrittmodus
        failed = false;
        for (int i = 1; i < 5; i++) {
            String command = String.format("%1$dZg", i);
            String response = sendCommand(command, 500);
            if (!response.contains("+16")) {
                throw new Exception("Schrittmodus falsch");
            }
        }
        
        initializeDigitalOutputs(digitalOutputsBank1);
        initializeDigitalOutputs(digitalOutputsBank2);
    }

    @Override
    public void setEnabled(boolean enabled) throws Exception {
        if (enabled && !connected) {
            connect();
        }
    }

    @Override
    public void home(ReferenceHead head) throws Exception {
        initializeAxis(Z_AXIS);
        initializeAxis(X_AXIS);
        initializeAxis(Y_AXIS);
        
		// Absolute Positionierung
		setAbsolutePositioning(X_AXIS);
		setAbsolutePositioning(Y_AXIS);
		setAbsolutePositioning(Z_AXIS);
		setAbsolutePositioning(C_AXIS);
		
		this.x = readAxisPosition(X_AXIS);
		this.y = readAxisPosition(Y_AXIS);
		this.z = readAxisPosition(Z_AXIS);
		this.c = readAxisPosition(C_AXIS);
    	
    	digitalOutputsBank1.light = true;
    	digitalOutputsBank2.light = true;
        setDigitalOutputs(digitalOutputsBank1);
        setDigitalOutputs(digitalOutputsBank2);
    }
    
	private double readAxisPosition(int axis) throws Exception {
		String command = String.format("%1$dC", axis);
		String response = sendCommand(command);
		String posString = response.split("C")[1];
		Double pos = Integer.parseInt(posString.substring(1)) / stepsPerMm[axis];
		return pos;
	}
    
    private void moveAxisTo(int axis, double position) throws Exception {
    	int steps = new Double(position * stepsPerMm[axis]).intValue();
    	Logger.debug(String.format("Axis: %1d, Position: %2f, Steps: %3d", axis, position, steps));
    	
    	// Position setzen
    	String command = String.format("%1$ds%2$d", axis, steps);
		String response = sendCommand(command);
        if (!response.equals(command)) {
            throw new Exception("Verfahren gescheitert");
        }
        
        // Motor starten
        startMotor(axis);
    }
    
    private void waitForMoveCompleted(int axis) throws Exception {
    	// wait for axis to become ready again
        DriverStatus status = new DriverStatus();
		do {
			Thread.sleep(100);
			status = getAxisStatus(axis);
		} while (!status.ready);
    }
    
    private void setFeedRate(int axis, double scale) throws Exception {
    	int frequency = new Double(Math.abs(stepsPerMm[axis]) * (feedMmPerMinute[axis] / 60.0) * scale).intValue();
    	String command = String.format("%1$do%2$d", axis, frequency);
        String response = sendCommand(command);
        Logger.trace(response);
        if (!response.equals(command)) {
            throw new Exception("Feedrate setzen gescheitert");
        }
    }
    
    private void setAcceleration(int axis) throws Exception {
    	int frequency_per_s = new Double(Math.abs(stepsPerMm[axis]) * (accelMmPerSecondSquared[axis] / 60.0)).intValue();
    	String command = String.format("%1$d:accel%2$d", axis, frequency_per_s);
        String response = sendCommand(command);
        Logger.trace(response);
        if (!response.equals(command)) {
            throw new Exception("Acceleration setzen gescheitert");
        }
    }

    @Override
    public void moveTo(ReferenceHeadMountable hm, Location location, double speed)
            throws Exception {
    	
    	// Verfahrweg einstellen: s<int>
    	// Absolute Positionierung: p2

    	location = location.subtract(hm.getHeadOffsets());
        location = location.convertToUnits(LengthUnit.Millimeters);

        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        double c = location.getRotation();
        boolean xMoved = false;
        boolean yMoved = false;
        boolean zMoved = false;
        boolean cMoved = false;
        
        // correct C axis offset
        double xCorrection = (Math.sin(-c / 180.0 * Math.PI) * this.cAxisCorrectionRadius);
        double yCorrection = (Math.cos(-c / 180.0 * Math.PI) * this.cAxisCorrectionRadius);
        x += xCorrection;
        y += yCorrection;

        if (!Double.isNaN(x) && ((x != this.x) || (xCorrection != this.xCorrection)) && checkLimit(X_AXIS, x)) {
        	setFeedRate(X_AXIS, speed);
            moveAxisTo(X_AXIS, x);
            xMoved = true;
        }
        if (!Double.isNaN(y) && ((y != this.y) || (yCorrection != this.yCorrection)) && checkLimit(Y_AXIS, y)) {
        	setFeedRate(Y_AXIS, speed);
            moveAxisTo(Y_AXIS, y);
            yMoved = true;
        }
        if (!Double.isNaN(z) && (z != this.z) && checkLimit(Z_AXIS, z) && !hm.toString().contains("Camera")) {
        	setFeedRate(Z_AXIS, speed);
            moveAxisTo(Z_AXIS, z);
            zMoved = true;
        }
        if (!Double.isNaN(c) && c != this.c) {
        	setFeedRate(C_AXIS, speed);
            moveAxisTo(C_AXIS, c);
            cMoved = true;
        }
        
        if (xMoved) {
            waitForMoveCompleted(X_AXIS);
            this.x = readAxisPosition(X_AXIS) - xCorrection;
            this.xCorrection = xCorrection;
        }
        if (yMoved) {
            waitForMoveCompleted(Y_AXIS);
            this.y = readAxisPosition(Y_AXIS) - yCorrection;
            this.yCorrection = yCorrection;
        }
        if (zMoved) {
            waitForMoveCompleted(Z_AXIS);
            this.z = readAxisPosition(Z_AXIS);
        }
        if (cMoved) {
            waitForMoveCompleted(C_AXIS);
            this.c = readAxisPosition(C_AXIS);
        }
    }

    @Override
    public void pick(ReferenceNozzle nozzle) throws Exception {
        digitalOutputsBank2.suck = true;
        digitalOutputsBank2.blow = false;
        setDigitalOutputs(digitalOutputsBank2);
    }

    @Override
    public void place(ReferenceNozzle nozzle) throws Exception {
    	digitalOutputsBank2.suck = false;
        digitalOutputsBank2.blow = true;
        setDigitalOutputs(digitalOutputsBank2);
        Thread.sleep(this.placeBlowTime);
        digitalOutputsBank2.blow = false;
        setDigitalOutputs(digitalOutputsBank2);
    }

    @Override
    public void actuate(ReferenceActuator actuator, double value) throws Exception {
        // TODO Auto-generated method stub

    }

    @Override
    public void actuate(ReferenceActuator actuator, boolean on) throws Exception {
        if (actuator.getIndex() == 0) {
            sendCommand(on ? "M8" : "M9");
        }
    }

    @Override
    public Location getLocation(ReferenceHeadMountable hm) {
        return new Location(LengthUnit.Millimeters, x, y, z, c).add(hm.getHeadOffsets());
    }

    public synchronized void disconnect() {
        disconnectRequested = true;
        connected = false;

        try {
            if (readerThread != null && readerThread.isAlive()) {
                readerThread.join();
            }
        }
        catch (Exception e) {
            Logger.error("disconnect()", e);
        }

        try {
            super.disconnect();
        }
        catch (Exception e) {
            Logger.error("disconnect()", e);
        }
        disconnectRequested = false;
    }

    public String sendCommand(String command) throws Exception {
        return sendCommand(command, -1);
    }

    public synchronized String sendCommand(String command, long timeout) throws Exception {
        String response;
        command = "#" + command;
        synchronized (commandLock) {
            lastResponse = null;
            if (command != null) {
                Logger.debug("sendCommand({}, {})", command, timeout);
                output.write(command.getBytes());
                output.write("\r".getBytes());
            }
            if (timeout == -1) {
                commandLock.wait();
            }
            else {
                commandLock.wait(timeout);
            }
            response = lastResponse;
        }
        if (response == null) {
            throw new Exception("Command did not return a response");
        }

        return response;
    }

    public void run() {
        while (!disconnectRequested) {
            String line;
            try {
                line = readLine().trim();
            }
            catch (TimeoutException ex) {
                continue;
            }
            catch (IOException e) {
                Logger.error("Read error", e);
                return;
            }
            line = line.trim();
            Logger.trace(line);
            lastResponse = line;
            synchronized (commandLock) {
                commandLock.notifyAll();
            }
        }
    }

    @Override
    public Wizard getConfigurationWizard() {
        // TODO Auto-generated method stub
        return new AbstractSerialPortDriverConfigurationWizard(this);
    }

    @Override
    public String getPropertySheetHolderTitle() {
        return getClass().getSimpleName();
    }

    @Override
    public PropertySheetHolder[] getChildPropertySheetHolders() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public PropertySheet[] getPropertySheets() {
        return new PropertySheet[] {new PropertySheetWizardAdapter(getConfigurationWizard())};
    }

    @Override
    public Action[] getPropertySheetHolderActions() {
        // TODO Auto-generated method stub
        return null;
    }
}
