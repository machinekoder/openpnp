package org.openpnp.spi;

import org.openpnp.model.Job;

public interface JobProcessor extends PropertySheetHolder, WizardConfigurable {
    void initialize(Job job) throws Exception;

    /**
     * Shortcut for next(null).
     * @return
     * @throws JobProcessorException
     */
    boolean next() throws JobProcessorException;
    
    /**
     * Performs the given command. If the command is null the processor will perform
     * the next logical command given the state of the processor.
     * @param command
     * @return
     * @throws JobProcessorException
     */
    boolean next(JobProcessorCommand command) throws JobProcessorException;
    
    void abort() throws Exception;
    
    public void addTextStatusListener(TextStatusListener listener);

    public void removeTextStatusListener(TextStatusListener listener);
    
    public interface JobProcessorCommand {
        String getName(); // Skip Placement, Skip Board
        String getDescription(); // Skip the current placement, Skip all placements on the current board.
        void execute() throws JobProcessorException;
    }
    
    public class JobProcessorException extends Exception {
        protected final JobProcessorCommand[] commands;
        
        public JobProcessorException(JobProcessorCommand[] commands, Exception cause) {
            super(cause);
            this.commands = commands;
        }
        
        public JobProcessorCommand[] getCommands() {
            return commands;
        }
    }
    
    public interface TextStatusListener {
        public void textStatus(String text);
    }
}
