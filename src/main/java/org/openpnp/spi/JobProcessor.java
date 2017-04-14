package org.openpnp.spi;

import org.openpnp.model.Job;

public interface JobProcessor extends PropertySheetHolder, WizardConfigurable {
    void initialize(Job job) throws Exception;

    /**
     * Shortcut for next(null).
     * @return
     * @throws Exception: Note that a JobProcessorException can be thrown instead, which can
     * include Commands to help resolve the problem.
     */
    boolean next() throws Exception;
    
    /**
     * Performs the given command. If the command is null the processor will perform
     * the next logical command given the state of the processor.
     * @param command
     * @return
     * @throws Exception: Note that a JobProcessorException can be thrown instead, which can
     * include Commands to help resolve the problem.
     */
    boolean next(JobProcessorCommand command) throws Exception;
    
    void abort() throws Exception;
    
    public void addTextStatusListener(TextStatusListener listener);

    public void removeTextStatusListener(TextStatusListener listener);
    
    public interface JobProcessorCommand {
        String getName(); // Skip Placement, Skip Board
        String getDescription(); // Skip the current placement, Skip all placements on the current board.
        void execute() throws Exception;
    }
    
    public class JobProcessorException extends Exception {
        protected final JobProcessorCommand[] commands;
        
        public JobProcessorException(Exception cause, JobProcessorCommand... commands) {
            super(cause);
            this.commands = commands;
        }
        
        public JobProcessorCommand[] getCommands() {
            return commands;
        }

        @Override
        public String getMessage() {
            return getCause().getMessage();
        }
    }
    
    public interface TextStatusListener {
        public void textStatus(String text);
    }
}
