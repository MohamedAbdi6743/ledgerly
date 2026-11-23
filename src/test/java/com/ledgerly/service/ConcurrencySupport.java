package com.ledgerly.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Shared by the concurrency tests. */
final class ConcurrencySupport {

	private ConcurrencySupport() {
	}

	/** Starts every task at the same instant (a starting gate), then waits for all of them. */
	static <T> List<T> runAllAtOnce(List<Callable<T>> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		try {
			CountDownLatch gate = new CountDownLatch(1);
			List<Future<T>> futures = new ArrayList<>();
			for (Callable<T> task : tasks) {
				futures.add(pool.submit(() -> {
					gate.await();
					return task.call();
				}));
			}
			gate.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get()); // rethrows anything unexpected, failing the test
			}
			return results;
		} finally {
			pool.shutdownNow();
		}
	}

}
