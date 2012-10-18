/*
 * Copyright 2012 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package azkaban.jobExecutor;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import azkaban.jobExecutor.utils.process.AzkabanProcess;
import azkaban.jobExecutor.utils.process.AzkabanProcessBuilder;
import azkaban.utils.Props;

/*
 * A job that runs a simple unix command
 * 
 */
public class ProcessJob extends AbstractProcessJob {

	public static final String COMMAND = "command";
    private static final long KILL_TIME_MS = 5000;
    private volatile AzkabanProcess process;

	public ProcessJob(final String jobId, final Props props, final Logger log) {
		super(jobId, props, log);
	}

	@Override
	public void run() throws Exception {
		resolveProps();
		List<String> commands = getCommandList();

		long startMs = System.currentTimeMillis();

		info(commands.size() + " commands to execute.");
		File[] propFiles = initPropsFiles();
		Map<String, String> envVars = getEnvironmentVariables();

		for (String command : commands) {
			AzkabanProcessBuilder builder = new AzkabanProcessBuilder(partitionCommandLine(command))
					.setEnv(envVars)
					.setWorkingDir(getCwd())
					.setLogger(getLog());

			info("Command: " + builder.getCommandString());
			if (builder.getEnv().size() > 0) {
				info("Environment variables: " + builder.getEnv());
			}
			info("Working directory: " + builder.getWorkingDir());

			boolean success = false;
			this.process = builder.build();

			try {
				this.process.run();
				success = true;
			} catch (Exception e) {
				for (File file : propFiles)
					if (file != null && file.exists())
						file.delete();
				throw new RuntimeException(e);
			} finally {
				this.process = null;
				info("Process completed " + (success ? "successfully" : "unsuccessfully") + " in "
						+ ((System.currentTimeMillis() - startMs) / 1000) + " seconds.");
			}
		}

		// Get the output properties from this job.
		generateProperties(propFiles[1]);

		for (File file : propFiles)
			if (file != null && file.exists())
				file.delete();
	}


	protected List<String> getCommandList() {
		List<String> commands = new ArrayList<String>();
		commands.add(_props.getString(COMMAND));
		for (int i = 1; _props.containsKey(COMMAND + "." + i); i++) {
			commands.add(_props.getString(COMMAND + "." + i));
		}

		return commands;
	}

    @Override
    public void cancel() throws InterruptedException {
        if(process == null)
            throw new IllegalStateException("Not started.");
        boolean killed = process.softKill(KILL_TIME_MS, TimeUnit.MILLISECONDS);
        if(!killed) {
            warn("Kill with signal TERM failed. Killing with KILL signal.");
            process.hardKill();
        }
    }

    @Override
    public double getProgress() {
        return process != null && process.isComplete()? 1.0 : 0.0;
    }
    
	public int getProcessId() {
		return process.getProcessId();
	}

	@Override
	public Props getProps() {
		return _props;
	}

	public String getPath() {
		return _jobPath;
	}

	public String getJobName() {
		return getId();
	}

	/**
	 * Splits the command into a unix like command line structure. Quotes and
	 * single quotes are treated as nested strings.
	 * 
	 * @param command
	 * @return
	 */
	public static String[] partitionCommandLine(final String command) {
		ArrayList<String> commands = new ArrayList<String>();

		int index = 0;

		StringBuffer buffer = new StringBuffer(command.length());

		boolean isApos = false;
		boolean isQuote = false;
		while (index < command.length()) {
			char c = command.charAt(index);

			switch (c) {
			case ' ':
				if (!isQuote && !isApos) {
					String arg = buffer.toString();
					buffer = new StringBuffer(command.length() - index);
					if (arg.length() > 0) {
						commands.add(arg);
					}
				} else {
					buffer.append(c);
				}
				break;
			case '\'':
				if (!isQuote) {
					isApos = !isApos;
				} else {
					buffer.append(c);
				}
				break;
			case '"':
				if (!isApos) {
					isQuote = !isQuote;
				} else {
					buffer.append(c);
				}
				break;
			default:
				buffer.append(c);
			}

			index++;
		}

		if (buffer.length() > 0) {
			String arg = buffer.toString();
			commands.add(arg);
		}

		return commands.toArray(new String[commands.size()]);
	}
}
