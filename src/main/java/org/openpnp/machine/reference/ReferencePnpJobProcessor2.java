package org.openpnp.machine.reference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.PartAlignment;
import org.openpnp.spi.PnpJobProcessor.JobPlacement.Status;
import org.openpnp.spi.base.AbstractPnpJobProcessor;
import org.simpleframework.xml.Attribute;

/**
 * TODO STOPSHIP: For the "don't bother me" option, we could either use a default command
 * or just mark the placement skipped and store the error. I think the latter. Errors unrelated
 * to a placement will still prompt, but those are early in the process. 
 */
public class ReferencePnpJobProcessor2 extends AbstractPnpJobProcessor {
    protected Job job;
    protected JobProcessorCommand nextCommand;

    @Attribute(required = false)
    protected boolean parkWhenComplete = false;

    protected Machine machine;

    protected Head head;

    protected List<JobPlacement> jobPlacements = new ArrayList<>();

    protected List<PlannedPlacement> plannedPlacements = new ArrayList<>();

    protected Map<BoardLocation, Location> boardLocationFiducialOverrides = new HashMap<>();

    long startTime;
    int totalPartsPlaced;


    @Override
    public void initialize(Job job) throws Exception {
        if (this.job != null) {
            // TODO: Perform abort, cleanup
            this.job = null;
        }
        this.job = job;
        nextCommand = PreFlight;
    }

    @Override
    public boolean next() throws Exception {
        return next(null);
    }

    @Override
    public boolean next(JobProcessorCommand command) throws Exception {
        if (command == null) {
            if (nextCommand == null) {
                return false;
            }
            nextCommand.execute();
        }
        else {
            command.execute();
        }
        return true;
    }

    @Override
    public void abort() throws Exception {
        AbortJob.execute();
    }

    final JobProcessorCommand PreFlight = new Command() {
        public void execute() throws Exception {
            startTime = System.currentTimeMillis();
            totalPartsPlaced = 0;

            // Create some shortcuts for things that won't change during the run
            machine = Configuration.get().getMachine();
            head = machine.getDefaultHead();
            
            jobPlacements.clear();
            boardLocationFiducialOverrides.clear();

            fireTextStatus("Checking job for setup errors.");

            for (BoardLocation boardLocation : job.getBoardLocations()) {
                // Only check enabled boards
                if (!boardLocation.isEnabled()) {
                    continue;
                }
                for (Placement placement : boardLocation.getBoard().getPlacements()) {
                    // Ignore placements that aren't set to be placed
                    if (placement.getType() != Placement.Type.Place) {
                        continue;
                    }

                    // Ignore placements that aren't on the side of the board we're processing.
                    if (placement.getSide() != boardLocation.getSide()) {
                        continue;
                    }

                    // TODO STOPSHIP: Needs to come from pre-made list or setting it to skip
                    // doesn't work
                    // TODO STOPSHIP: Something to think about: If a user can enable/disable parts
                    // and boards and anything else, the jobplacement list will become out of
                    // date.
                    JobPlacement jobPlacement = new JobPlacement(boardLocation, placement);

                    try {
                        // Make sure the part is not null
                        if (placement.getPart() == null) {
                            throw new Exception(
                                    String.format("Part not found for board %s, placement %s.",
                                            boardLocation.getBoard().getName(), placement.getId()));
                        }

                        // Verify that the part height is greater than zero. Catches a common
                        // configuration
                        // error.
                        if (placement.getPart().getHeight().getValue() <= 0D) {
                            throw new Exception(
                                    String.format("Part height for %s must be greater than 0.",
                                            placement.getPart().getId()));
                        }
                    }
                    catch (Exception e) {
                        throw new JobProcessorException(e, new SkipPlacement(jobPlacement));
                    }

                    try {
                        // Make sure there is at least one compatible nozzle tip available
                        findNozzleTip(head, placement.getPart());

                        // Make sure there is at least one compatible and enabled feeder available
                        findFeeder(machine, placement.getPart());
                    }
                    catch (Exception e) {
                        throw new JobProcessorException(e, new SkipPart(placement.getPart()));
                    }

                    jobPlacements.add(jobPlacement);
                }
            }

            // Everything looks good, so prepare the machine.
            fireTextStatus("Preparing machine.");

            // Safe Z the machine
            head.moveToSafeZ();
            // Discard any currently picked parts
            discardAll(head);
            
            nextCommand = FiducialCheck;
        }
    };

    final JobProcessorCommand FiducialCheck = new Command() {
        public void execute() throws JobProcessorException {
            // if fail, create new DisableBoard command, passing the board so that execute
            // can disable it. don't set nextComand so this gets called again, and make
            // sure to check which boards are enabled so we process them right.
            nextCommand = null;
        }
    };

    final JobProcessorCommand Plan = new Command() {
        public void execute() throws JobProcessorException {

        }
    };

    final JobProcessorCommand SkipBoardFiducialCheck = new Command("Skip Fiducial Check", "") {
        public void execute() throws JobProcessorException {
            // set board check fids = false
        }
    };

    final JobProcessorCommand DisableBoard = new Command("Disable Board",
            "Disable the currently processing board so it is not processed any further.") {
        public void execute() throws JobProcessorException {
            // set boardlocation.enabled = false
        }
    };

    final JobProcessorCommand AbortJob = new Command("Abort Job",
            "Abort the currently running job and perform the cleanup routine.") {
        public void execute() throws JobProcessorException {}
    };
    
    class SkipPlacement extends Command {
        final JobPlacement jobPlacement;
        
        public SkipPlacement(JobPlacement jobPlacement) {
            super("Skip Placement", "Skip the current placement.");
            this.jobPlacement = jobPlacement;
        }
        
        public void execute() throws JobProcessorException {
            jobPlacement.status = JobPlacement.Status.Skipped;
            // TODO: Likely need to remove the PlannedPlacement, if any.
        }
    }

    class SkipPart extends Command {
        final Part part;
        
        public SkipPart(Part part) {
            super("Skip Part", "Skip the current part .");
            this.part = part;
        }
        
        public void execute() throws JobProcessorException {
            // TODO: What does it do?
//            jobPlacement.status = JobPlacement.Status.Skipped;
            // TODO: Likely need to remove the PlannedPlacement, if any.
        }
    }

    public List<JobPlacement> getJobPlacementsById(String id) {
        return jobPlacements.stream().filter((jobPlacement) -> {
            return jobPlacement.toString() == id;
        }).collect(Collectors.toList());
    }

    public List<JobPlacement> getJobPlacementsById(String id, Status status) {
        return jobPlacements.stream().filter((jobPlacement) -> {
            return jobPlacement.toString() == id && jobPlacement.status == status;
        }).collect(Collectors.toList());
    }

    abstract class Command implements JobProcessorCommand {
        final String name;
        final String description;

        public Command() {
            this(null, null);
        }

        public Command(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }
    }
    
    public static class PlannedPlacement {
        public final JobPlacement jobPlacement;
        public final Nozzle nozzle;
        public Feeder feeder;
        public PartAlignment.PartAlignmentOffset alignmentOffsets;
        public boolean fed;
        public boolean stepComplete;

        public PlannedPlacement(Nozzle nozzle, JobPlacement jobPlacement) {
            this.nozzle = nozzle;
            this.jobPlacement = jobPlacement;
        }

        @Override
        public String toString() {
            return nozzle + " -> " + jobPlacement.toString();
        }
    }

    
}
