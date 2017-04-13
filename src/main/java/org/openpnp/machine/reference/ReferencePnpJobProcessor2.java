package org.openpnp.machine.reference;

import java.util.List;

import org.openpnp.model.Job;
import org.openpnp.spi.PnpJobProcessor.JobPlacement.Status;
import org.openpnp.spi.base.AbstractPnpJobProcessor;

/**
 * Status: Originally had the idea of next() throwing an exception with RecoveryOptions, then
 * next would attempt to do the same task and pass the recovery option. Then I thought that
 * everything could be a command, including the recovery options, but then we might need
 * commands as specific as SkipPlacementForPick since we need to set status related to the
 * current command.
 * 
 * But now I am thinking maybe not. 
 *
 * Each command would be responsible for setting nextCommand when it finishes successfully. Otherwise
 * it's just gonna get called again. Before it does, maybe a command is called that changes the
 * state of the job, so each command should check if it needs to run. If not, just drop through.
 * 
 * *****Is this really very different then the FSM?
 *      For one thing, it allows multiple recovery options.
 *      
 * Note: We always need to be able to Pause and Abort, so I do think Abort needs to be
 * interface level and not a command.      
 * 
 * Note: Most of the reponse commands will need to be classes so we can create them with required
 * state.
 *  
 * Note: When we get to the ability to specify a recovery option on a JobPlacement, it should be
 * Continue, not Skip, indicating that the command will do whatever it can to continue, including
 * skipping if required.
 * 
 * PreFlight
 * FiducialCheck
 * Plan
 *  
 */
public class ReferencePnpJobProcessor2 extends AbstractPnpJobProcessor {
    protected Job job;
    protected JobProcessorCommand nextCommand;
    
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
    public boolean next() throws JobProcessorException {
        return next(null);
    }

    @Override
    public boolean next(JobProcessorCommand command) throws JobProcessorException {
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

    @Override
    public List<JobPlacement> getJobPlacementsById(String id) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public List<JobPlacement> getJobPlacementsById(String id, Status status) {
        // TODO Auto-generated method stub
        return null;
    }
    
    final JobProcessorCommand PreFlight = new Command() {
        public void execute() throws JobProcessorException {
            nextCommand = FiducialCheck;
        }
    };
    
    final JobProcessorCommand FiducialCheck = new Command() {
        public void execute() throws JobProcessorException {
            // if fail, create new DisableBoard command, passing the board so that execute
            // can disable it. don't set nextComand so this gets called again, and make
            // sure to check which boards are enabled so we process them right.
            nextCommand = Plan;
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
    
    final JobProcessorCommand DisableBoard = new Command("Disable Board", "Disable the currently processing board so it is not processed any further.") {
        public void execute() throws JobProcessorException {
            // set boardlocation.enabled = false
        }
    };
    
    final JobProcessorCommand AbortJob = new Command("Abort Job", "Abort the currently running job and perform the cleanup routine.") {
        public void execute() throws JobProcessorException {
        }
    };
    
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
}
