package hudson.scm;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import hudson.model.AbstractModelObject;
import hudson.model.AbstractProject;
import hudson.model.Hudson;
import hudson.scm.SubversionRepositoryStatus.JobProvider;
import hudson.scm.SubversionSCM.ModuleLocation;
import hudson.scm.SubversionSCM.SvnInfo;
import hudson.triggers.SCMTrigger;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;

public class SubversionCloudForgeStatus extends AbstractModelObject {
	private static class Status {
		boolean scmFound;
		boolean triggerFound;
		boolean rootFound;
		boolean pathFound;
	}

	private static final Logger LOGGER = Logger.getLogger(SubversionCloudForgeStatus.class.getName());

	private static boolean doesIgnorePostCommitHooks(SCMTrigger trigger) {
		if (IS_IGNORE_POST_COMMIT_HOOKS_METHOD == null) return false;
	
		try {
			return (Boolean) IS_IGNORE_POST_COMMIT_HOOKS_METHOD.invoke(trigger);
		} catch (Exception e) {
			LOGGER.log(WARNING, "Failure when calling isIgnorePostCommitHooks",	e);
			return false;
		}
	}

	private static Method getIsIgnorePostCommitHooksMethod() {
		try {
			return SCMTrigger.class.getMethod("isIgnorePostCommitHooks", (Class[]) null);
		} catch (NoSuchMethodException e) {
			return null;
		}
	}

	private static final Method IS_IGNORE_POST_COMMIT_HOOKS_METHOD = getIsIgnorePostCommitHooksMethod();

	private JobProvider jobProvider = new JobProvider() {
		@SuppressWarnings("rawtypes")
		@Override
		public List<AbstractProject> getAllJobs() {
			return Hudson.getInstance().getAllItems(AbstractProject.class);
		}
	};

	// for tests
	void setJobProvider(JobProvider provider) {
		jobProvider = provider;
	}

	@Override
	public String getDisplayName() {
		return "cloudforge";
	}

	@Override
	public String getSearchUrl() {
		return "cloudforge";
	}

	@RequirePOST
	public void doNotifyCommit(StaplerRequest req, StaplerResponse rsp)	throws IOException {

		final String project  = req.getParameter("project");
		final String domain   = req.getParameter("organization");
		final String revision = req.getParameter("youngest");
		final String changed  = req.getParameter("changed");

		final Set<String> paths = new HashSet<String>(Arrays.asList(changed.split("\n")));
		long rev = -1;
		if (revision != null) try {
			rev = Long.parseLong(revision);
		} catch (NumberFormatException e) {
			LOGGER.log(INFO, "Ignoring bad revision " + revision, e);
		}

		final int port = -1; // port < 0 is ignored
		final String host = domain + ".svn.cloudforge.com";
		final SVNURL root;
		try {
			root = SVNURL.create("https", null, host, port, project, false);
		} catch (SVNException e) {
			LOGGER.log(WARNING,	"Failed to handle Subversion commit notification", e);
			rsp.setStatus(SC_BAD_REQUEST);
			return;
		}

		final Status stat = new Status();
		for (AbstractProject<?, ?> p : jobProvider.getAllJobs()) {
			if (p.isDisabled()) continue;
			try {
				final SCM scm = p.getScm();
				if (scm instanceof SubversionSCM)
					stat.scmFound = true;
				else
					continue;

				final SCMTrigger trigger = p.getTrigger(SCMTrigger.class);
				if (trigger != null && !doesIgnorePostCommitHooks(trigger))
					stat.triggerFound = true;
				else
					continue;

				final SubversionSCM sscm = (SubversionSCM) scm;

				final List<SvnInfo> infos = new ArrayList<SvnInfo>();

				boolean projectmatches = false;
				for (final ModuleLocation loc : sscm.getProjectLocations(p)) {
					if (loc.getRepositoryRoot(p).equals(root))
						stat.rootFound = true;
					else
						continue;

					final String m = loc.getSVNURL().getPath();
					final String n = loc.getRepositoryRoot(p).getPath();
					if (!m.startsWith(n)) continue; // repository root should be a subpath of the module path, but be defensive

					String remaining = m.substring(n.length());
					if (remaining.startsWith("/")) remaining = remaining.substring(1);
					final String remainingslash = remaining + '/';

					if (rev != -1) {
						infos.add(new SvnInfo(loc.getURL(), rev));
					}

					for (final String path : paths) {
						if (path.equals(remaining) /* for files */
						 || path.startsWith(remainingslash) /* for dirs */
						 || remaining.length() == 0 /* when someone is checking out the whole repo
						  (that is, m==n)*/) {
							// this project is possibly changed. poll now.
							// if any of the data we used was bogus, the trigger will not detect a change
							projectmatches = true;
							stat.pathFound = true;
						}
					}
				}

				if (projectmatches) {
					LOGGER.fine("Scheduling the immediate polling of " + p);

					final RevisionParameterAction[] actions =  infos.isEmpty() ? new RevisionParameterAction[0] :
						new RevisionParameterAction[] { new RevisionParameterAction(infos) };

					trigger.run(actions);
				}

			} catch (SVNException e) {
				LOGGER.log(WARNING,	"Failed to handle Subversion commit notification", e);
			}
		}

		if (!stat.scmFound)          LOGGER.warning("No subversion jobs found");
		else if (!stat.triggerFound) LOGGER.warning("No subversion jobs using SCM polling or all jobs using SCM polling are ignoring post-commit hooks");
		else if (!stat.rootFound)    LOGGER.warning("No subversion jobs using repository: " + root);
		else if (!stat.pathFound)    LOGGER.fine("No jobs found matching the modified files");

		rsp.setStatus(SC_OK);
	}
}
